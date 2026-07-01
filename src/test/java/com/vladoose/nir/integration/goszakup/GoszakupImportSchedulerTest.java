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
        when(service.importMedicalTenders(null)).thenAnswer(inv -> { seen.set(MarketContext.get()); return new ImportSummary(); });

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
        when(service.importMedicalTenders(null)).thenReturn(new ImportSummary());
        new GoszakupImportScheduler(service, true).tick();
        verify(service, times(1)).importMedicalTenders(null);
        MarketContext.clear();
    }
}
