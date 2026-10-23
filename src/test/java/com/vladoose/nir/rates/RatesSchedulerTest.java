package com.vladoose.nir.rates;

import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.nbrk.NbrkRate;
import com.vladoose.nir.integration.nbrk.NbrkRatesClient;
import com.vladoose.nir.service.rates.RatesScheduler;
import com.vladoose.nir.service.rates.RatesWriter;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class RatesSchedulerTest {

    /** 22.10.2026 23:30 UTC = 23.10 04:30 по Уральску: дата курса — по Уральску. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T23:30:00Z"), ZoneOffset.UTC);

    @Test
    void run_fetchesTodayByOral_andStores() {
        AtomicReference<LocalDate> asked = new AtomicReference<>();
        NbrkRatesClient client = d -> { asked.set(d); return List.of(new NbrkRate("RUB", new BigDecimal("5.48"))); };
        RatesWriter writer = mock(RatesWriter.class);
        when(writer.store(any(), anyList(), any())).thenReturn(1);
        RatesScheduler s = new RatesScheduler(client, writer, true, "", CLOCK);
        assertThat(s.run()).isEqualTo(1);
        assertThat(asked.get()).isEqualTo(LocalDate.of(2026, 10, 23));
        verify(writer).store(eq(LocalDate.of(2026, 10, 23)), anyList(), any());
    }

    @Test
    void fetchFailure_keepsLastRate_noThrow() {
        NbrkRatesClient client = d -> { throw new UpstreamException("Нацбанк РК: HTTP 500"); };
        RatesWriter writer = mock(RatesWriter.class);
        RatesScheduler s = new RatesScheduler(client, writer, true, "", CLOCK);
        assertThatCode(s::runSafe).doesNotThrowAnyException();
        verifyNoInteractions(writer);                                       // прежние курсы не тронуты
    }

    @Test
    void disabled_registersNothing() {
        ScheduledTaskRegistrar reg = new ScheduledTaskRegistrar();
        new RatesScheduler(d -> List.of(), mock(RatesWriter.class), false, "", CLOCK).configureTasks(reg);
        assertThat(reg.getCronTaskList()).isEmpty();
        new RatesScheduler(d -> List.of(), mock(RatesWriter.class), true, "", CLOCK).configureTasks(reg);
        assertThat(reg.getCronTaskList()).hasSize(1);
    }
}
