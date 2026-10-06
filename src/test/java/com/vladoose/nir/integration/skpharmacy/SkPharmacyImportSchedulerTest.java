package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.integration.goszakup.ImportSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class SkPharmacyImportSchedulerTest {

    /** Прогон, упавший целиком, — ошибка с причиной: итоговый тост станет красным, а не зелёным. */
    @Test
    void crashedRun_reportsTheReason() throws Exception {
        SkPharmacyImportService service = mock(SkPharmacyImportService.class);
        doThrow(new IllegalStateException("Connection to localhost:5432 refused"))
                .when(service).fillImport(any(ImportSummary.class));

        SkPharmacyImportScheduler scheduler = new SkPharmacyImportScheduler(service, false);
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

        SkPharmacyImportScheduler scheduler = new SkPharmacyImportScheduler(service, false);
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

