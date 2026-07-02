package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GoszakupImportSchedulerTest {

    @Test
    void run_setsKzMarketContext_aroundServiceCall() {
        GoszakupImportService service = mock(GoszakupImportService.class);
        AtomicReference<Market> seen = new AtomicReference<>();
        doAnswer(inv -> { seen.set(MarketContext.get()); return null; })
                .when(service).fillImport(eq(null), any(ImportSummary.class));

        GoszakupImportScheduler scheduler = new GoszakupImportScheduler(service, true);
        scheduler.run();

        assertThat(seen.get()).isEqualTo(Market.KZ);      // KZ во время вызова
        assertThat(MarketContext.get()).isEqualTo(Market.RF); // очищено после (дефолт RF)
    }

    @Test
    void tick_disabled_doesNotCallService() {
        GoszakupImportService service = mock(GoszakupImportService.class);
        new GoszakupImportScheduler(service, false).tick();
        verifyNoInteractions(service);
    }

    @Test
    void tick_enabled_callsService() {
        GoszakupImportService service = mock(GoszakupImportService.class);
        new GoszakupImportScheduler(service, true).tick();
        verify(service, times(1)).fillImport(eq(null), any(ImportSummary.class));
        MarketContext.clear();
    }

    @Test
    void startAsync_returnsImmediately_thenCompletesInBackground() throws Exception {
        GoszakupImportService service = mock(GoszakupImportService.class);
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> { hold.await(); return null; })
                .when(service).fillImport(eq("ЗКО"), any(ImportSummary.class));

        GoszakupImportScheduler scheduler = new GoszakupImportScheduler(service, false);
        var st = scheduler.startAsync("ЗКО");

        assertThat(st.running()).isTrue();          // вернулись сразу, импорт в фоне
        assertThat(st.lastSummary()).isNotNull();   // живой прогресс-объект уже отдан

        hold.countDown();
        long deadline = System.currentTimeMillis() + 3000;
        while (scheduler.status().running() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(scheduler.status().running()).isFalse();
        assertThat(scheduler.status().lastFinishedAt()).isNotNull();
    }

    @Test
    void status_reflectsLastRun() {
        GoszakupImportService service = mock(GoszakupImportService.class);
        doAnswer(inv -> { ((ImportSummary) inv.getArgument(1)).setCreated(3); return null; })
                .when(service).fillImport(eq("ЗКО"), any(ImportSummary.class));

        GoszakupImportScheduler scheduler = new GoszakupImportScheduler(service, false);
        assertThat(scheduler.status().running()).isFalse();
        assertThat(scheduler.status().lastFinishedAt()).isNull(); // ещё не бегали

        scheduler.run("ЗКО");

        var st = scheduler.status();
        assertThat(st.running()).isFalse();
        assertThat(st.lastFinishedAt()).isNotNull();
        assertThat(st.lastRegion()).isEqualTo("ЗКО");
        assertThat(st.lastSummary().getCreated()).isEqualTo(3);
        MarketContext.clear();
    }
}
