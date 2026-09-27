package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import com.vladoose.nir.service.LeadStatusPushWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class WestmedLeadSyncTest {

    @Autowired LeadIntakeService intake;
    @Autowired LeadStatusPushWriter pushWriter;
    @Autowired LeadRepository leadRepository;
    @Autowired LeadService leadService;

    FakeWestmedClient fake;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        fake = new FakeWestmedClient();
    }

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    private WestmedLeadSync sync(int pageSize, boolean writeStatus) {
        return new WestmedLeadSync(fake, new WestmedLeadMapper("https://westmed.kz"), intake, pushWriter, pageSize, writeStatus);
    }

    private static WestmedPriceRequest price(String status) {
        String id = "zz-" + UUID.randomUUID();
        return new WestmedPriceRequest(id, "Клиент " + id, id + "@zz.kz", null, null, "Нужен аппарат", null,
                status, "2026-09-20T08:15:30Z");
    }

    private Lead lead(WestmedKind kind, String siteId) {
        return leadRepository.findBySourceAndExternalId(LeadSources.WESTMED, kind.externalId(siteId)).orElseThrow();
    }

    /** Импортировать NEW-заявку и взять её в работу → у обращения появится pending «PROCESSED». */
    private WestmedPriceRequest importedAndTaken(WestmedLeadSync s) {
        WestmedPriceRequest r = price("NEW");
        fake.priceRequests.add(0, r);
        s.runOnce();
        leadService.take(lead(WestmedKind.PRICE, r.id()).getId(), "admin");
        return r;
    }

    @Test
    void firstRunWalksAllPagesLaterRunsStopAtFirstPageWithoutNews() {
        for (int i = 0; i < 5; i++) fake.priceRequests.add(price("NEW"));
        WestmedLeadSync s = sync(2, false);

        assertThat(s.runOnce().created).isEqualTo(5);
        assertThat(fake.pricePages).containsExactly(0, 1, 2);

        fake.pricePages.clear();
        fake.priceRequests.add(0, price("NEW"));             // сайт отдаёт новые сверху
        assertThat(s.runOnce().created).isEqualTo(1);
        assertThat(fake.pricePages).containsExactly(0, 1);   // на стр. 1 новых нет → стоп

        fake.pricePages.clear();
        assertThat(s.runOnce().created).isZero();
        assertThat(fake.pricePages).containsExactly(0);
    }

    @Test
    void quoteRequestsBecomeKzLeadsWithItems() {
        String id = "zz-" + UUID.randomUUID();
        fake.quoteRequests.add(new WestmedQuoteRequest(id, "Айгерим", id + "@zz.kz", null, "ТОО «ZZ»", null, "NEW",
                List.of(new WestmedQuoteRequest.Item("obn-150", "Облучатель ОБН-150", 2)), "2026-09-20T08:15:30Z"));

        sync(50, false).runOnce();

        Lead l = lead(WestmedKind.QUOTE, id);
        assertThat(l.getSubject()).isEqualTo("Запрос КП");
        assertThat(l.getMarket()).isEqualTo(Market.KZ);
        assertThat(l.getItems()).extracting(LeadItem::getName, LeadItem::getQuantity, LeadItem::getProductUrl)
                .containsExactly(tuple("Облучатель ОБН-150", 2, "https://westmed.kz/product/obn-150"));
    }

    @Test
    void historyKeepsSiteStatusesWithoutQueueingWrites() {
        WestmedPriceRequest done = price("CLOSED");
        WestmedPriceRequest taken = price("PROCESSED");
        fake.priceRequests.add(done);
        fake.priceRequests.add(taken);

        sync(50, true).runOnce();

        assertThat(lead(WestmedKind.PRICE, done.id()).getStatus()).isEqualTo(LeadStatus.CLOSED);
        Lead t = lead(WestmedKind.PRICE, taken.id());
        assertThat(t.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(t.getExtStatus()).isEqualTo("PROCESSED");
        assertThat(t.getExtStatusPending()).isNull();
        assertThat(fake.statusUpdates).noneMatch(u -> u.contains(done.id()) || u.contains(taken.id()));
    }

    @Test
    void takenLeadIsPushedToSiteAndPendingCleared() {
        WestmedLeadSync s = sync(50, true);
        WestmedPriceRequest r = importedAndTaken(s);

        s.runOnce();

        assertThat(fake.statusUpdates).contains("PRICE:" + r.id() + "=PROCESSED");
        Lead l = lead(WestmedKind.PRICE, r.id());
        assertThat(l.getExtStatus()).isEqualTo("PROCESSED");
        assertThat(l.getExtStatusPending()).isNull();
        assertThat(l.getExtSyncError()).isNull();
        assertThat(l.getEvents()).anyMatch(e -> e.getType() == LeadEventType.SYNC && e.getBody().contains("В работе"));
    }

    @Test
    void failedPushKeepsPendingAndRecordsError() {
        WestmedLeadSync s = sync(50, true);
        WestmedPriceRequest r = importedAndTaken(s);
        fake.failUpdatesWith = new WestmedApiException(503, "HTTP 503 на PATCH /api/admin/requests/x/status");

        WestmedSyncResult res = s.runOnce();

        assertThat(res.errors).isGreaterThanOrEqualTo(1);
        Lead l = lead(WestmedKind.PRICE, r.id());
        assertThat(l.getExtStatusPending()).isEqualTo("PROCESSED");   // повторим на следующем цикле
        assertThat(l.getExtSyncError()).contains("503");
    }

    @Test
    void leadDeletedOnSiteStopsRetrying() {
        WestmedLeadSync s = sync(50, true);
        WestmedPriceRequest r = importedAndTaken(s);
        fake.missingOnSite.add(r.id());

        s.runOnce();

        Lead l = lead(WestmedKind.PRICE, r.id());
        assertThat(l.getExtStatusPending()).isNull();
        assertThat(l.getExtSyncError()).contains("больше нет");
    }

    @Test
    void writeStatusOffNeverCallsSite() {
        WestmedLeadSync s = sync(50, false);
        WestmedPriceRequest r = importedAndTaken(s);

        s.runOnce();

        assertThat(fake.statusUpdates).noneMatch(u -> u.contains(r.id()));
        assertThat(lead(WestmedKind.PRICE, r.id()).getExtStatusPending()).isEqualTo("PROCESSED");
    }
}
