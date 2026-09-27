package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LeadCreateRequest;
import com.vladoose.nir.dto.request.LeadItemDto;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadServiceTest {

    @Autowired LeadService service;
    @Autowired LeadIntakeService intake;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Lead siteLead(LeadStatus status, String extStatus) {
        return siteLead(status, extStatus, "Иван", "Нужен облучатель");
    }

    private Lead siteLead(LeadStatus status, String extStatus, String name, String message) {
        return intake.ingest(new IncomingLead(LeadSources.WESTMED, "price:zz-" + System.nanoTime(), LeadChannel.SITE,
                "Запрос цены", null, name, null, "ivan-" + System.nanoTime() + "@zz.kz", null, message,
                List.of(new IncomingLead.Item("Облучатель ОБН-150", "Азов", 1, null)),
                status, extStatus, null)).orElseThrow();
    }

    private static LeadCreateRequest manual(LeadChannel channel, String phone, String email) {
        LeadCreateRequest r = new LeadCreateRequest();
        r.setChannel(channel);
        r.setContactName("Звонивший");
        r.setContactPhone(phone);
        r.setContactEmail(email);
        r.setMessage("Ищут УЗИ-аппарат для гинекологии");
        return r;
    }

    private static LeadItemDto item(String name, String brand, int qty) {
        LeadItemDto d = new LeadItemDto();
        d.setName(name);
        d.setBrand(brand);
        d.setQuantity(qty);
        return d;
    }

    private static LeadEvent last(Lead l) { return l.getEvents().get(l.getEvents().size() - 1); }

    @Test
    void takeMovesNewToInWorkAndQueuesSiteStatus() {
        Lead l = siteLead(LeadStatus.NEW, "NEW");

        Lead after = service.take(l.getId(), "admin");

        assertThat(after.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(after.getExtStatusPending()).isEqualTo("PROCESSED");
        assertThat(last(after).getType()).isEqualTo(LeadEventType.STATUS);
        assertThat(last(after).getAuthor()).isEqualTo("admin");
        assertThat(last(after).getBody()).isEqualTo("Новое → В работе");
    }

    @Test
    void takeIsRejectedWhenNotNew() {
        Lead l = siteLead(LeadStatus.IN_WORK, "PROCESSED");
        assertThatThrownBy(() -> service.take(l.getId(), "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("взять в работу");
    }

    @Test
    void closeNeedsReasonAndStoresReasonAndComment() {
        Lead l = siteLead(LeadStatus.NEW, "NEW");
        assertThatThrownBy(() -> service.close(l.getId(), null, null, "admin")).isInstanceOf(BadRequestException.class);

        Lead closed = service.close(l.getId(), LeadCloseReason.SPAM, "реклама", "admin");

        assertThat(closed.getStatus()).isEqualTo(LeadStatus.CLOSED);
        assertThat(closed.getCloseReason()).isEqualTo(LeadCloseReason.SPAM);
        assertThat(closed.getExtStatusPending()).isEqualTo("CLOSED");
        assertThat(last(closed).getBody()).contains("спам").contains("реклама");
    }

    @Test
    void pendingIsDroppedWhenSiteAlreadyHasTheTargetStatus() {
        Lead l = siteLead(LeadStatus.IN_WORK, "PROCESSED");     // на сайте уже «В работе»
        service.close(l.getId(), LeadCloseReason.DUPLICATE, null, "admin");

        Lead reopened = service.reopen(l.getId(), "admin");    // снова «В работе» — писать на сайт нечего

        assertThat(reopened.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(reopened.getCloseReason()).isNull();
        assertThat(reopened.getExtStatusPending()).isNull();
    }

    @Test
    void manualLeadIsInWorkAndNeverQueuesSiteStatus() {
        Lead l = service.createManual(manual(LeadChannel.PHONE, "8 777 000 00 01", null), "admin");

        assertThat(l.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(l.getSource()).isEqualTo(LeadSources.MANUAL);
        assertThat(l.getExternalId()).isNull();
        assertThat(l.getSubject()).isEqualTo("Звонок");
        assertThat(l.getPhoneNorm()).isEqualTo("+77770000001");
        assertThat(l.getEvents().get(0).getAuthor()).isEqualTo("admin");
        assertThat(l.getEvents().get(0).getBody()).endsWith("внесено вручную");

        Lead closed = service.close(l.getId(), LeadCloseReason.ANSWERED, null, "admin");
        assertThat(closed.getExtStatusPending()).isNull();
    }

    @Test
    void manualLeadNeedsPhoneOrEmail() {
        assertThatThrownBy(() -> service.createManual(manual(LeadChannel.WHATSAPP, " ", null), "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("телефон или email");
        assertThat(service.createManual(manual(LeadChannel.WHATSAPP, null, "x@zz.kz"), "admin").getSubject())
                .isEqualTo("WhatsApp");
    }

    @Test
    void manualLeadCannotPretendToBeSite() {
        assertThatThrownBy(() -> service.createManual(manual(LeadChannel.SITE, "+77770000001", null), "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void callNeedsDirectionAndIsStoredAsPhoneEvent() {
        Lead l = siteLead(LeadStatus.NEW, "NEW");
        assertThatThrownBy(() -> service.addEvent(l.getId(), LeadEventType.CALL, null, "перезвонил", "admin"))
                .isInstanceOf(BadRequestException.class);

        Lead after = service.addEvent(l.getId(), LeadEventType.CALL, LeadDirection.OUT, "уточнил модель", "admin");

        assertThat(last(after).getType()).isEqualTo(LeadEventType.CALL);
        assertThat(last(after).getDirection()).isEqualTo(LeadDirection.OUT);
        assertThat(last(after).getChannel()).isEqualTo(LeadChannel.PHONE);
        assertThat(last(after).getBody()).isEqualTo("уточнил модель");
    }

    @Test
    void systemEventTypesCannotBeAddedByHand() {
        Lead l = siteLead(LeadStatus.NEW, "NEW");
        assertThatThrownBy(() -> service.addEvent(l.getId(), LeadEventType.STATUS, null, "x", "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void itemsAreReplacedWholesaleOnlyBeforeConversion() {
        Lead l = siteLead(LeadStatus.NEW, "NEW");

        Lead after = service.updateItems(l.getId(), List.of(item("Облучатель ОБН-75", "Азов", 3), item("  ", null, 1)), "admin");

        assertThat(after.getItems()).extracting(LeadItem::getName, LeadItem::getQuantity, LeadItem::getLineNo)
                .containsExactly(tuple("Облучатель ОБН-75", 3, 1));
        service.close(l.getId(), LeadCloseReason.OTHER, null, "admin");
        assertThatThrownBy(() -> service.updateItems(l.getId(), List.of(item("Х", null, 1)), "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void leadOfAnotherMarketIsNotFound() {
        Lead l = siteLead(LeadStatus.NEW, "NEW");
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.get(l.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listFiltersByStatusChannelAndSearchText() {
        String tag = "ZZТег" + System.nanoTime();
        Lead fresh = siteLead(LeadStatus.NEW, "NEW", tag + " Иванов", "Нужен облучатель");
        Lead inWork = siteLead(LeadStatus.IN_WORK, "PROCESSED", "Петров", "Ищем " + tag);

        assertThat(service.list(EnumSet.of(LeadStatus.NEW), null, tag))
                .extracting(Lead::getId).containsExactly(fresh.getId());
        assertThat(service.list(EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK), null, tag.toLowerCase()))
                .extracting(Lead::getId).containsExactlyInAnyOrder(fresh.getId(), inWork.getId());
        assertThat(service.list(EnumSet.allOf(LeadStatus.class), LeadChannel.PHONE, tag)).isEmpty();
    }

    @Test
    void countIsPerMarket() {
        long kzBefore = service.count(LeadStatus.NEW);
        MarketContext.set(Market.RF);
        long rfBefore = service.count(LeadStatus.NEW);
        MarketContext.set(Market.KZ);

        siteLead(LeadStatus.NEW, "NEW");

        assertThat(service.count(LeadStatus.NEW)).isEqualTo(kzBefore + 1);
        MarketContext.set(Market.RF);
        assertThat(service.count(LeadStatus.NEW)).isEqualTo(rfBefore);
    }
}
