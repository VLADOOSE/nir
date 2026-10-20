package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.tendernotify.ImportOutageNotifier;
import com.vladoose.nir.service.tendernotify.NewTenderNotifier;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SkPharmacyImportSchedulerTest {

    final NewTenderNotifier notifier = mock(NewTenderNotifier.class);
    final ImportOutageNotifier outage = mock(ImportOutageNotifier.class);

    /** В конце прогона — уведомление о созданных им тендерах, ещё под рынком KZ. */
    @Test
    void finishedRun_notifiesWithItsSummary_underKzMarket() throws Exception {
        SkPharmacyImportService service = mock(SkPharmacyImportService.class);
        doAnswer(inv -> { ((ImportSummary) inv.getArgument(0)).addCreated("521464-1"); return null; })
                .when(service).fillImport(any(ImportSummary.class));
        AtomicReference<Market> marketAtNotify = new AtomicReference<>();
        doAnswer(inv -> { marketAtNotify.set(MarketContext.get()); return null; })
                .when(notifier).afterImport(any(ImportSummary.class));

        SkPharmacyImportScheduler scheduler = new SkPharmacyImportScheduler(service, notifier, outage);
        scheduler.startAsync();
        long deadline = System.currentTimeMillis() + 3000;
        while (scheduler.status().running() && System.currentTimeMillis() < deadline) Thread.sleep(20);

        verify(notifier).afterImport(argThat(s -> s != null && s.getCreatedExtIds().contains("521464-1")));
        assertThat(marketAtNotify.get()).isEqualTo(Market.KZ);
    }

    /** Прогон, упавший целиком, — ошибка с причиной: итоговый тост станет красным, а не зелёным. */
    @Test
    void crashedRun_reportsTheReason() throws Exception {
        SkPharmacyImportService service = mock(SkPharmacyImportService.class);
        doThrow(new IllegalStateException("Connection to localhost:5432 refused"))
                .when(service).fillImport(any(ImportSummary.class));

        SkPharmacyImportScheduler scheduler = new SkPharmacyImportScheduler(service, notifier, outage);
        scheduler.startAsync();
        long deadline = System.currentTimeMillis() + 3000;
        while (scheduler.status().running() && System.currentTimeMillis() < deadline) Thread.sleep(20);

        ImportSummary s = scheduler.status().lastSummary();
        assertThat(s.getErrors()).isEqualTo(1);
        assertThat(s.getLastError()).isEqualTo("прогон прерван: Connection to localhost:5432 refused");
        assertThat(s.getMessage()).isEqualTo("Ошибка импорта СК-Фармации: Connection to localhost:5432 refused");
    }

    /** Error (не Exception) раньше проглатывался: прогон кончался «без ошибок». Подкласс Error — не настоящий OOM (§14). */
    @Test
    void errorInRun_isReportedToo() throws Exception {
        SkPharmacyImportService service = mock(SkPharmacyImportService.class);
        doThrow(new FakeHeapError("Java heap space")).when(service).fillImport(any(ImportSummary.class));

        SkPharmacyImportScheduler scheduler = new SkPharmacyImportScheduler(service, notifier, outage);
        scheduler.startAsync();
        long deadline = System.currentTimeMillis() + 3000;
        while (scheduler.status().running() && System.currentTimeMillis() < deadline) Thread.sleep(20);

        assertThat(scheduler.status().running()).isFalse();
        assertThat(scheduler.status().lastSummary().getLastError()).isEqualTo("прогон прерван: Java heap space");
    }

    private static final class FakeHeapError extends Error {
        FakeHeapError(String message) { super(message); }
    }

    /** Итог каждого прогона — и упавшего целиком — уходит в оповещение о сбоях, с названием площадки. */
    @Test
    void finishedRun_evenCrashed_reportedToOutageNotifier() throws Exception {
        SkPharmacyImportService service = mock(SkPharmacyImportService.class);
        doThrow(new IllegalStateException("PKIX path building failed")).when(service).fillImport(any(ImportSummary.class));

        SkPharmacyImportScheduler scheduler = new SkPharmacyImportScheduler(service, notifier, outage);
        scheduler.startAsync();
        long deadline = System.currentTimeMillis() + 3000;
        while (scheduler.status().running() && System.currentTimeMillis() < deadline) Thread.sleep(20);

        verify(outage).afterRun(eq("СК-Фармации"), argThat(s -> s != null && s.getErrors() == 1));
    }

    /** Порядок в конце прогона, как у goszakup: отметки → «🏥 Новый тендер» → «Наш товар в тендерах» → оповещение о сбое. */
    @Test
    void finishedRun_marksThenNewTenders_thenOurProducts_thenOutage() throws Exception {
        SkPharmacyImportService service = mock(SkPharmacyImportService.class);
        java.util.List<String> calls = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        doAnswer(inv -> { calls.add("new"); return null; }).when(notifier).afterImport(any(ImportSummary.class));
        doAnswer(inv -> { calls.add("outage"); return null; }).when(outage).afterRun(eq("СК-Фармации"), any(ImportSummary.class));

        SkPharmacyImportScheduler scheduler = new SkPharmacyImportScheduler(service, notifier, outage,
                sum -> calls.add("marks"), sum -> { calls.add("ours"); throw new IllegalStateException("Telegram"); });
        scheduler.startAsync();
        long deadline = System.currentTimeMillis() + 3000;
        while ((scheduler.status().running() || calls.size() < 4) && System.currentTimeMillis() < deadline) Thread.sleep(20);

        assertThat(calls).containsExactly("marks", "new", "ours", "outage");
    }
}
