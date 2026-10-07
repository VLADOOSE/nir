package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.service.tendernotify.NewTenderNotifier;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GoszakupImportSchedulerTest {

    final NewTenderNotifier notifier = mock(NewTenderNotifier.class);

    static void awaitIdle(GoszakupImportScheduler scheduler) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (scheduler.status().running() && System.currentTimeMillis() < deadline) Thread.sleep(20);
    }

    @Test
    void startAsync_setsKzMarketContext_inBackgroundThread() throws Exception {
        GoszakupImportService service = mock(GoszakupImportService.class);
        AtomicReference<Market> seen = new AtomicReference<>();
        doAnswer(inv -> { seen.set(MarketContext.get()); return null; })
                .when(service).fillImport(eq(null), any(ImportSummary.class));

        GoszakupImportScheduler scheduler = new GoszakupImportScheduler(service, notifier);
        scheduler.startAsync(null);
        awaitIdle(scheduler);

        assertThat(seen.get()).isEqualTo(Market.KZ);      // KZ во время вызова, в потоке прогона
    }

    /** В конце прогона — уведомление о созданных им тендерах, ещё под рынком KZ (нотификатор читает базу). */
    @Test
    void finishedRun_notifiesWithItsSummary_underKzMarket() throws Exception {
        GoszakupImportService service = mock(GoszakupImportService.class);
        doAnswer(inv -> { ((ImportSummary) inv.getArgument(1)).addCreated("100-1"); return null; })
                .when(service).fillImport(eq(null), any(ImportSummary.class));
        AtomicReference<Market> marketAtNotify = new AtomicReference<>();
        doAnswer(inv -> { marketAtNotify.set(MarketContext.get()); return null; })
                .when(notifier).afterImport(any(ImportSummary.class));

        GoszakupImportScheduler scheduler = new GoszakupImportScheduler(service, notifier);
        scheduler.startAsync(null);
        awaitIdle(scheduler);

        verify(notifier).afterImport(argThat(s -> s != null && s.getCreatedExtIds().contains("100-1")));
        assertThat(marketAtNotify.get()).isEqualTo(Market.KZ);
    }

    /** Упавшее уведомление прогон не роняет: статус «не идёт», итог прогона на месте. */
    @Test
    void notifierFailure_doesNotBreakRun() throws Exception {
        GoszakupImportService service = mock(GoszakupImportService.class);
        doThrow(new IllegalStateException("boom")).when(notifier).afterImport(any(ImportSummary.class));

        GoszakupImportScheduler scheduler = new GoszakupImportScheduler(service, notifier);
        scheduler.startAsync(null);
        awaitIdle(scheduler);

        assertThat(scheduler.status().running()).isFalse();
        assertThat(scheduler.status().lastFinishedAt()).isNotNull();
        assertThat(scheduler.status().lastSummary().getErrors()).isZero();
    }

    @Test
    void startAsync_returnsImmediately_thenCompletesInBackground() throws Exception {
        GoszakupImportService service = mock(GoszakupImportService.class);
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> { hold.await(); return null; })
                .when(service).fillImport(eq("ЗКО"), any(ImportSummary.class));

        GoszakupImportScheduler scheduler = new GoszakupImportScheduler(service, notifier);
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
    void status_reflectsLastRun() throws Exception {
        GoszakupImportService service = mock(GoszakupImportService.class);
        doAnswer(inv -> { ((ImportSummary) inv.getArgument(1)).setCreated(3); return null; })
                .when(service).fillImport(eq("ЗКО"), any(ImportSummary.class));

        GoszakupImportScheduler scheduler = new GoszakupImportScheduler(service, notifier);
        assertThat(scheduler.status().running()).isFalse();
        assertThat(scheduler.status().lastFinishedAt()).isNull(); // ещё не бегали

        scheduler.startAsync("ЗКО");
        awaitIdle(scheduler);

        var st = scheduler.status();
        assertThat(st.running()).isFalse();
        assertThat(st.lastFinishedAt()).isNotNull();
        assertThat(st.lastRegion()).isEqualTo("ЗКО");
        assertThat(st.lastSummary().getCreated()).isEqualTo(3);
    }

    /** Прогон, упавший целиком (например, база недоступна), — ошибка с причиной, а не тихий конец без итога. */
    @Test
    void startAsync_crashedRun_reportsTheReason() throws Exception {
        GoszakupImportService service = mock(GoszakupImportService.class);
        doThrow(new IllegalStateException("Connection to localhost:5432 refused"))
                .when(service).fillImport(eq(null), any(ImportSummary.class));

        GoszakupImportScheduler scheduler = new GoszakupImportScheduler(service, notifier);
        scheduler.startAsync(null);
        long deadline = System.currentTimeMillis() + 3000;
        while (scheduler.status().running() && System.currentTimeMillis() < deadline) Thread.sleep(20);

        ImportSummary s = scheduler.status().lastSummary();
        assertThat(s.getErrors()).isEqualTo(1);
        assertThat(s.getLastError()).isEqualTo("прогон прерван: Connection to localhost:5432 refused");
        assertThat(s.getMessage()).isEqualTo("Импорт прерван: Connection to localhost:5432 refused");
    }

    /** Error (не Exception) раньше тонул в Future без следа: тост был бы зелёным. Подкласс Error — не настоящий OOM (§14). */
    @Test
    void startAsync_errorInRun_isReportedToo() throws Exception {
        GoszakupImportService service = mock(GoszakupImportService.class);
        doThrow(new FakeHeapError("Java heap space")).when(service).fillImport(eq(null), any(ImportSummary.class));

        GoszakupImportScheduler scheduler = new GoszakupImportScheduler(service, notifier);
        scheduler.startAsync(null);
        long deadline = System.currentTimeMillis() + 3000;
        while (scheduler.status().running() && System.currentTimeMillis() < deadline) Thread.sleep(20);

        assertThat(scheduler.status().running()).isFalse();
        assertThat(scheduler.status().lastSummary().getLastError()).isEqualTo("прогон прерван: Java heap space");
    }

    private static final class FakeHeapError extends Error {
        FakeHeapError(String message) { super(message); }
    }
}

