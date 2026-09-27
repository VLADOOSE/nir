package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadStatusPushWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** cycle() зовётся напрямую в потоке теста: так он входит в транзакцию теста и откатывается. */
@SpringBootTest
@Transactional
class WestmedLeadSchedulerTest {

    @Autowired LeadIntakeService intake;
    @Autowired LeadStatusPushWriter pushWriter;
    @Autowired LeadRepository leadRepository;

    FakeWestmedClient fake;

    @BeforeEach void setUp() { fake = new FakeWestmedClient(); }
    @AfterEach void tearDown() { MarketContext.clear(); }

    private WestmedLeadScheduler scheduler(boolean enabled) {
        WestmedLeadSync sync = new WestmedLeadSync(fake, new WestmedLeadMapper("https://westmed.kz"),
                intake, pushWriter, 50, false);
        return new WestmedLeadScheduler(sync, fake, enabled, false, "KZ", 600_000);
    }

    private static WestmedPriceRequest price() {
        String id = "zz-" + UUID.randomUUID();
        return new WestmedPriceRequest(id, "Клиент", id + "@zz.kz", null, null, "Нужен аппарат", null,
                "NEW", "2026-09-20T08:15:30Z");
    }

    @Test
    void cycleStampsMarketFromConfigEvenWhenThreadHasDefaultMarket() {
        WestmedPriceRequest r = price();
        fake.priceRequests.add(r);
        MarketContext.clear();   // у фонового потока рынка нет — дефолт RF (§6 CLAUDE.md)

        scheduler(true).cycle();

        String ext = WestmedKind.PRICE.externalId(r.id());
        MarketContext.set(Market.RF);
        assertThat(leadRepository.findBySourceAndExternalId(LeadSources.WESTMED, ext)).isEmpty();
        MarketContext.set(Market.KZ);
        Lead l = leadRepository.findBySourceAndExternalId(LeadSources.WESTMED, ext).orElseThrow();
        assertThat(l.getMarket()).isEqualTo(Market.KZ);
    }

    @Test
    void successIsReportedInStatus() {
        fake.priceRequests.add(price());
        WestmedLeadScheduler s = scheduler(true);

        s.cycle();

        assertThat(s.status().getLastError()).isNull();
        assertThat(s.status().getLastSuccessAt()).isNotNull();
        assertThat(s.status().getLastCreated()).isEqualTo(1);
        assertThat(s.status().isEnabled()).isTrue();
    }

    @Test
    void missingCredentialsAreReportedAndSiteIsNotCalled() {
        fake.configured = false;
        WestmedLeadScheduler s = scheduler(true);

        s.cycle();

        assertThat(s.status().getLastError()).contains("учётные данные");
        assertThat(fake.pricePages).isEmpty();
    }

    @Test
    void authFailurePausesFurtherCycles() {
        fake.failFetchWith = new WestmedAuthException("вход не удался: неверный логин или пароль");
        WestmedLeadScheduler s = scheduler(true);

        s.cycle();
        assertThat(s.status().getLastError()).contains("неверный логин").contains("повтор через 10 мин");

        fake.failFetchWith = null;
        s.cycle();
        assertThat(fake.pricePages).isEmpty();   // пауза: на сайт не ходили — не упираемся в лимит входов
    }

    @Test
    void siteDownIsReportedButNotPaused() {
        fake.failFetchWith = new WestmedApiException(0, "сайт недоступен: ConnectException");
        WestmedLeadScheduler s = scheduler(true);

        s.cycle();
        assertThat(s.status().getLastError()).contains("сайт недоступен");

        fake.failFetchWith = null;
        s.cycle();
        assertThat(fake.pricePages).containsExactly(0);
        assertThat(s.status().getLastError()).isNull();
    }

    @Test
    void manualRunIsRefusedWhenIntegrationIsOff() {
        assertThatThrownBy(() -> scheduler(false).runNow()).isInstanceOf(BadRequestException.class);
    }
}
