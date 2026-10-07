package com.vladoose.nir.integration;

import com.vladoose.nir.integration.goszakup.GoszakupImportScheduler;
import com.vladoose.nir.integration.skpharmacy.SkPharmacyImportScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TenderAutoImportSchedulerTest {

    final GoszakupImportScheduler goszakup = mock(GoszakupImportScheduler.class);
    final SkPharmacyImportScheduler sk = mock(SkPharmacyImportScheduler.class);

    @Test
    void enabled_startsBothInBackground() {
        new TenderAutoImportScheduler(goszakup, sk, true).tick();

        verify(goszakup).startAsync(null);
        verify(sk).startAsync();
    }

    @Test
    void disabled_startsNothing() {
        new TenderAutoImportScheduler(goszakup, sk, false).tick();

        verifyNoInteractions(goszakup, sk);
    }

    /** Расписание по умолчанию: ежечасно в :10 с 8 до 19 по Уральску, пн–сб; воскресенье и ночь — без прогонов. */
    @Test
    void defaultCron_workingHoursOralTime() {
        CronExpression cron = CronExpression.parse(TenderAutoImportScheduler.DEFAULT_CRON);
        ZoneId oral = ZoneId.of("Asia/Oral");

        ZonedDateTime fridayEvening = ZonedDateTime.of(2026, 10, 9, 19, 30, 0, 0, oral);
        assertThat(cron.next(fridayEvening)).isEqualTo(ZonedDateTime.of(2026, 10, 10, 8, 10, 0, 0, oral)); // суббота 8:10

        ZonedDateTime saturdayEvening = ZonedDateTime.of(2026, 10, 10, 19, 30, 0, 0, oral);
        assertThat(cron.next(saturdayEvening)).isEqualTo(ZonedDateTime.of(2026, 10, 12, 8, 10, 0, 0, oral)); // пн, воскресенье пропущено

        ZonedDateTime morning = ZonedDateTime.of(2026, 10, 7, 9, 0, 0, 0, oral);
        assertThat(cron.next(morning)).isEqualTo(ZonedDateTime.of(2026, 10, 7, 9, 10, 0, 0, oral));
    }
}
