package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.tendernotify.NewTenderNotifier;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SkPharmacyImportSchedulerTest {

    final NewTenderNotifier notifier = mock(NewTenderNotifier.class);

    /** В конце прогона — уведомление о созданных им тендерах, ещё под рынком KZ. */
    @Test
    void finishedRun_notifiesWithItsSummary_underKzMarket() throws Exception {
        SkPharmacyImportService service = mock(SkPharmacyImportService.class);
        doAnswer(inv -> { ((ImportSummary) inv.getArgument(0)).addCreated("521464-1"); return null; })
                .when(service).fillImport(any(ImportSummary.class));
        AtomicReference<Market> marketAtNotify = new AtomicReference<>();
        doAnswer(inv -> { marketAtNotify.set(MarketContext.get()); return null; })
                .when(notifier).afterImport(any(ImportSummary.class));

        SkPharmacyImportScheduler scheduler = new SkPharmacyImportScheduler(service, notifier);
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

        SkPharmacyImportScheduler scheduler = new SkPharmacyImportScheduler(service, notifier);
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

        SkPharmacyImportScheduler scheduler = new SkPharmacyImportScheduler(service, notifier);
        scheduler.startAsync();
        long deadline = System.currentTimeMillis() + 3000;
        while (scheduler.status().running() && System.currentTimeMillis() < deadline) Thread.sleep(20);

        assertThat(scheduler.status().running()).isFalse();
        assertThat(scheduler.status().lastSummary().getLastError()).isEqualTo("прогон прерван: Java heap space");
    }

    private static final class FakeHeapError extends Error {
        FakeHeapError(String message) { super(message); }
    }
}

