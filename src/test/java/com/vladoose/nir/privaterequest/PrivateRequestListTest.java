package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.dto.response.PrivateRequestListItemResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.PrivateRequestService.ListFilter;
import com.vladoose.nir.service.privaterequest.PrivateRequestStageCalculator;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PrivateRequestListTest {

    @Autowired PrivateRequestService service;
    @Autowired PrivateRequestStageCalculator stages;
    @Autowired PrivateRequestViewBuilder viewBuilder;
    @Autowired TenderRepository tenderRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository itemRepository;
    @Autowired LeadIntakeService intake;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private TenderLot lot(Tender t, int no, String name) {
        TenderLot l = TenderLot.builder().tender(t).lotNumber(no).equipName(name).quantity(1).build();
        t.getLots().add(l);
        em.persist(l);   // saveAndFlush(t) = merge управляемой заявки: в коллекцию встала бы копия, а l остался бы transient
        em.flush();
        return l;
    }

    private PriceRequest ask(Tender t, String status, TenderLot... lots) {
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZ Этап поставщик " + System.nanoTime()).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status(status).build());
        for (TenderLot l : lots) {
            pr.getItems().add(itemRepository.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(l).requestedQuantity(1).build()));
        }
        return pr;
    }

    @Test
    void stageFollowsData() {
        Tender t = service.createEmpty();
        assertThat(stages.of(t).text()).isEqualTo("Пустая");

        TenderLot a = lot(t, 1, "ZZ Позиция А");
        lot(t, 2, "ZZ Позиция Б");
        assertThat(stages.of(t).code()).isEqualTo("NEW");
        assertThat(stages.of(t).text()).isEqualTo("Новая");
        assertThat(stages.of(t).lineCount()).isEqualTo(2);

        ask(t, "SENT", a);
        PriceRequest answered = ask(t, "RESPONDED", a);
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("REQUESTED");
        assertThat(stages.of(t).text()).isEqualTo("Запрошены цены · 1 из 2 ответил");

        answered.getItems().get(0).setResponsePrice(new BigDecimal("18400.00"));
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("PRICED");
        assertThat(stages.of(t).text()).isEqualTo("Есть цены: 1 из 2 позиций");

        service.close(t.getId(), PrivateRequestStatus.WON);
        assertThat(stages.of(t).text()).isEqualTo("Закрыта: купили у нас");
        assertThat(viewBuilder.build(t).getStage()).isEqualTo("WON");
    }

    @Test
    void pluralForms() {
        assertThat(PrivateRequestStageCalculator.positionsGen(1)).isEqualTo("позиции");
        assertThat(PrivateRequestStageCalculator.positionsGen(2)).isEqualTo("позиций");
        assertThat(PrivateRequestStageCalculator.positionsGen(11)).isEqualTo("позиций");
        assertThat(PrivateRequestStageCalculator.positionsGen(21)).isEqualTo("позиции");
        assertThat(PrivateRequestStageCalculator.answeredWord(1)).isEqualTo("ответил");
        assertThat(PrivateRequestStageCalculator.answeredWord(5)).isEqualTo("ответили");
        assertThat(PrivateRequestStageCalculator.answeredWord(11)).isEqualTo("ответили");
    }

    @Test
    void listFiltersSearchesAndSortsNewestFirst() {
        Tender older = service.createEmpty();
        lot(older, 1, "ZZСПИСОК Облучатель ОБН-150");
        Tender newer = service.createEmpty();
        service.updateField(newer.getId(), PrivateRequestField.CONTACT_PHONE, "+7 799 911 22 33");
        service.updateField(newer.getId(), PrivateRequestField.CUSTOMER_NAME, "ZZСПИСОК Клиника Здоровье");
        Tender closed = service.createEmpty();
        service.close(closed.getId(), PrivateRequestStatus.LOST);
        em.flush();

        List<Long> open = service.list(ListFilter.OPEN, null).stream().map(Tender::getId).toList();
        assertThat(open).contains(older.getId(), newer.getId()).doesNotContain(closed.getId());
        assertThat(open.indexOf(newer.getId())).isLessThan(open.indexOf(older.getId()));
        assertThat(service.list(ListFilter.CLOSED, null)).extracting(Tender::getId).contains(closed.getId())
                .doesNotContain(older.getId());
        assertThat(service.list(ListFilter.ALL, null)).extracting(Tender::getId)
                .contains(older.getId(), newer.getId(), closed.getId());

        // уникальные токены: в nirdb живут настоящие заявки, их позиции и телефоны не должны попасть в выдачу
        assertThat(service.list(ListFilter.OPEN, "zzсписок облучатель")).extracting(Tender::getId).containsExactly(older.getId());
        assertThat(service.list(ListFilter.OPEN, "799 911 22")).extracting(Tender::getId).containsExactly(newer.getId());
        assertThat(service.list(ListFilter.OPEN, "zzсписок клиника")).extracting(Tender::getId).containsExactly(newer.getId());
        // % и _ из запроса — буквальные знаки, а не шаблон LIKE (иначе «zz%_» нашёл бы обе заявки)
        assertThat(service.list(ListFilter.OPEN, "zz%_нет")).isEmpty();
    }

    @Test
    void listItemCarriesStageClientAndLead() {
        Tender t = service.createEmpty();
        lot(t, 1, "ZZ Позиция");
        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, "ZZ Клиника Список");
        Lead lead = intake.ingest(new IncomingLead(LeadSources.WESTMED, "quote:zz-list-" + System.nanoTime(), LeadChannel.SITE,
                "Запрос КП", null, "Айгерим", "+7 777 000 00 08", null, null, null,
                List.of(), LeadStatus.NEW, "NEW", null)).orElseThrow();
        lead.setPrivateRequest(t);
        em.flush();

        PrivateRequestListItemResponse item = viewBuilder.list(List.of(t)).get(0);

        assertThat(item.getNumber()).isEqualTo(t.getTenderNumber());
        assertThat(item.getStage()).isEqualTo("NEW");
        assertThat(item.getStageText()).isEqualTo("Новая");
        assertThat(item.getClientLabel()).isEqualTo("ZZ Клиника Список");
        assertThat(item.getLineCount()).isEqualTo(1);
        assertThat(item.getLeadId()).isEqualTo(lead.getId());
        assertThat(item.getCreatedAt()).isNotNull();
    }
}
