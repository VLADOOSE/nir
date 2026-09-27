package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.service.LeadIntakeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadIntakeServiceTest {

    @Autowired LeadIntakeService intake;
    @Autowired FacilityRepository facilityRepository;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    /** 7 уникальных цифр: номера вида +7 799 XXXXXXX в живой nirdb не встречаются. */
    private static String uniq7() {
        return String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000L));
    }

    private static IncomingLead site(String ext, String phone, String email, List<IncomingLead.Item> items,
                                     LeadStatus status, String extStatus) {
        return new IncomingLead("zz-site", ext, LeadChannel.SITE, "Запрос КП",
                OffsetDateTime.parse("2026-09-20T08:15:30Z"), "Айгерим", phone, email, "ТОО «ZZ Клиника»",
                "Нужен облучатель", items, status, extStatus, null);
    }

    private static IncomingLead site(String ext, String phone, String email) {
        return site(ext, phone, email,
                List.of(new IncomingLead.Item("Облучатель ОБН-150", "Азов", 2, "https://westmed.kz/product/obn")),
                LeadStatus.NEW, "NEW");
    }

    private static String ext() { return "zz-" + System.nanoTime(); }

    @Test
    void createsLeadWithItemsReceivedEventAndNormalizedPhone() {
        Lead l = intake.ingest(site(ext(), "8 (777) 000-11-22", "a@zz.kz")).orElseThrow();

        assertThat(l.getMarket()).isEqualTo(Market.KZ);
        assertThat(l.getStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(l.getContactPhone()).isEqualTo("8 (777) 000-11-22");
        assertThat(l.getPhoneNorm()).isEqualTo("+77770001122");
        assertThat(l.getReceivedAt()).isEqualTo(OffsetDateTime.parse("2026-09-20T08:15:30Z"));
        assertThat(l.getExtStatus()).isEqualTo("NEW");
        assertThat(l.getItems()).extracting(LeadItem::getName, LeadItem::getBrand, LeadItem::getQuantity)
                .containsExactly(tuple("Облучатель ОБН-150", "Азов", 2));
        assertThat(l.getEvents()).hasSize(1);
        LeadEvent received = l.getEvents().get(0);
        assertThat(received.getType()).isEqualTo(LeadEventType.RECEIVED);
        assertThat(received.getDirection()).isEqualTo(LeadDirection.IN);
        assertThat(received.getChannel()).isEqualTo(LeadChannel.SITE);
        assertThat(received.getBody()).contains("Запрос КП").contains("1 поз.").contains("zz-site");
    }

    @Test
    void secondIngestWithSameExternalIdIsDuplicate() {
        String e = ext();
        assertThat(intake.ingest(site(e, null, "a@zz.kz"))).isPresent();
        assertThat(intake.ingest(site(e, null, "a@zz.kz"))).isEmpty();
        assertThat(intake.isKnown("zz-site", e)).isTrue();
    }

    @Test
    void matchesClientByLast10DigitsOfPhone() {
        String d = uniq7();
        Facility f = facilityRepository.save(Facility.builder()
                .name("ZZ Клиника тел " + System.nanoTime()).phone("+7 (799) " + d).market(Market.KZ).build());

        Lead l = intake.ingest(site(ext(), "8799" + d, null)).orElseThrow();

        assertThat(l.getFacility()).isNotNull();
        assertThat(l.getFacility().getId()).isEqualTo(f.getId());
    }

    @Test
    void matchesClientByEmailWhenPhoneGivesNothing() {
        String email = "Info@ZZ-" + System.nanoTime() + ".kz";
        Facility f = facilityRepository.save(Facility.builder()
                .name("ZZ Клиника почта " + System.nanoTime()).email(email).market(Market.KZ).build());

        Lead l = intake.ingest(site(ext(), null, email.toLowerCase())).orElseThrow();

        assertThat(l.getFacility().getId()).isEqualTo(f.getId());
    }

    @Test
    void ambiguousPhoneLeavesClientEmpty() {
        String d = uniq7();
        facilityRepository.save(Facility.builder().name("ZZ Дубль А " + System.nanoTime()).phone("+7799" + d).market(Market.KZ).build());
        facilityRepository.save(Facility.builder().name("ZZ Дубль Б " + System.nanoTime()).phone("8 799 " + d).market(Market.KZ).build());

        assertThat(intake.ingest(site(ext(), "+7799" + d, null)).orElseThrow().getFacility()).isNull();
    }

    @Test
    void clientOfAnotherMarketIsNotMatched() {
        String d = uniq7();
        facilityRepository.save(Facility.builder().name("ZZ РФ клиника " + System.nanoTime()).phone("+7799" + d).market(Market.RF).build());

        assertThat(intake.ingest(site(ext(), "+7799" + d, null)).orElseThrow().getFacility()).isNull();
    }

    @Test
    void importedFromHistoryKeepsSourceStatusWithEvent() {
        Lead l = intake.ingest(site(ext(), null, "h@zz.kz", List.of(), LeadStatus.CLOSED, "CLOSED")).orElseThrow();

        assertThat(l.getStatus()).isEqualTo(LeadStatus.CLOSED);
        assertThat(l.getExtStatusPending()).isNull();
        assertThat(l.getEvents()).extracting(LeadEvent::getType).containsExactly(LeadEventType.RECEIVED, LeadEventType.STATUS);
        assertThat(l.getEvents().get(1).getBody()).isEqualTo("Импортировано со статусом источника «Закрыта»");
    }

    @Test
    void blankItemsAreSkippedAndQuantityIsAtLeastOne() {
        Lead l = intake.ingest(site(ext(), null, "b@zz.kz",
                List.of(new IncomingLead.Item("  ", null, 1, null), new IncomingLead.Item("Шприц 5 мл", null, 0, null)),
                LeadStatus.NEW, "NEW")).orElseThrow();

        assertThat(l.getItems()).extracting(LeadItem::getName, LeadItem::getQuantity, LeadItem::getLineNo)
                .containsExactly(tuple("Шприц 5 мл", 1, 1));
    }
}
