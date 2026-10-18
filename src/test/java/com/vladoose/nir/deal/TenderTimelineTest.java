package com.vladoose.nir.deal;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.DealController;
import com.vladoose.nir.controller.TenderController;
import com.vladoose.nir.dto.request.DealEventRequest;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** История тендера (спека 2026-10-18 §4.2) и пути тендера. */
@SpringBootTest
@Transactional
class TenderTimelineTest {

    @Autowired DealController controller;
    @Autowired TenderController tenderController;
    @Autowired TenderRepository tenders;
    @Autowired ActivityApplyRepository applies;
    @Autowired PrivateRequestService requests;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void historyHasFoundApplyDatesAndNotes() {
        Tender t = tenders.save(Tender.builder().tenderNumber("ZZ-T-" + System.nanoTime()).status("ACTIVE")
                .platform(TenderPlatform.GOSZAKUP).build());
        ActivityApply a = applies.save(ActivityApply.builder().tender(t).status("WON").contractNumber("15")
                .contractSignedAt(LocalDate.of(2026, 10, 25)).paidAt(LocalDate.of(2026, 11, 10)).build());
        controller.tenderEvent(t.getId(), new DealEventRequest(DealEventType.CALL, "звонил заказчику"));
        em.flush();
        List<DealTimelineItem> items = controller.tenderTimeline(t.getId(), null, 50).items();
        assertThat(items).extracting(DealTimelineItem::kind)
                .contains("TENDER_FOUND", "APPLY_CREATED", "CONTRACT", "PAID", "CALL");
        assertThat(items).filteredOn(i -> "TENDER_FOUND".equals(i.kind())).extracting(DealTimelineItem::text)
                .containsExactly("Тендер найден на goszakup.gov.kz");
        assertThat(items).filteredOn(i -> "CONTRACT".equals(i.kind())).singleElement().satisfies(i -> {
            assertThat(i.text()).isEqualTo("Договор № 15 подписан");
            assertThat(i.dateOnly()).isTrue();
            assertThat(i.linkType()).isEqualTo("APPLY");
            assertThat(i.linkId()).isEqualTo(a.getId());
        });
        assertThat(controller.tenderProgress(t.getId()).steps()).hasSize(7);
    }

    /** Тендер, импортированный до V26: даты создания нет — строка истории по дате публикации, первой в дне. */
    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void oldTenderWithoutCreatedAtShowsPublishDay() {
        Tender t = tenders.save(Tender.builder().tenderNumber("ZZ-T-" + System.nanoTime()).status("ACTIVE")
                .platform(TenderPlatform.GOSZAKUP).publishDate(LocalDate.of(2026, 10, 8)).build());
        em.flush();
        em.createNativeQuery("update tender set created_at = null where id = :id").setParameter("id", t.getId())
                .executeUpdate();
        em.clear();
        controller.tenderEvent(t.getId(), new DealEventRequest(DealEventType.CALL, "звонил заказчику"));
        em.flush();
        List<DealTimelineItem> items = controller.tenderTimeline(t.getId(), null, 50).items();
        assertThat(items.get(0)).satisfies(i -> {
            assertThat(i.kind()).isEqualTo("TENDER_FOUND");
            assertThat(i.text()).isEqualTo("Тендер опубликован на goszakup.gov.kz");
            assertThat(i.dateOnly()).isTrue();
            assertThat(i.at()).isEqualTo(LocalDate.of(2026, 10, 8).atStartOfDay(Market.KZ.zone()).toOffsetDateTime());
        });

        Tender bare = tenders.save(Tender.builder().tenderNumber("ZZ-T-" + System.nanoTime()).status("ACTIVE").build());
        em.flush();
        em.createNativeQuery("update tender set created_at = null where id = :id").setParameter("id", bare.getId())
                .executeUpdate();
        em.clear();
        assertThat(controller.tenderTimeline(bare.getId(), null, 50).items()).isEmpty();
    }

    /** Чип лота «Есть ТЗ» и шаг «Лоты и ТЗ» читают один признак — статус разбора ТЗ доезжает до лота. */
    @Test
    @WithMockUser(roles = "USER")
    void lotResponseCarriesTechSpecStatus() {
        Tender t = tenders.save(Tender.builder().tenderNumber("ZZ-T-" + System.nanoTime()).status("ACTIVE")
                .platform(TenderPlatform.GOSZAKUP).build());
        t.getLots().add(TenderLot.builder().tender(t).lotNumber(1).equipName("Коробка").quantity(1)
                .requiredSpec("Дезсредства").techSpecStatus(TechSpecStatus.PENDING).build());
        em.flush();
        assertThat(tenderController.getLots(t.getId())).singleElement()
                .satisfies(l -> assertThat(l.getTechSpecStatus()).isEqualTo(TechSpecStatus.PENDING));
    }

    @Test
    @WithMockUser(roles = "USER")
    void guardsAndRights() {
        Tender pr = requests.createEmpty();
        Tender t = tenders.save(Tender.builder().tenderNumber("ZZ-T-" + System.nanoTime()).status("ACTIVE").build());
        assertThatThrownBy(() -> controller.tenderTimeline(pr.getId(), null, 50)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.tenderProgress(pr.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.tenderEvent(t.getId(), new DealEventRequest(DealEventType.NOTE, "x")))
                .isInstanceOf(AccessDeniedException.class);
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> controller.tenderTimeline(t.getId(), null, 50)).isInstanceOf(NotFoundException.class);
    }
}
