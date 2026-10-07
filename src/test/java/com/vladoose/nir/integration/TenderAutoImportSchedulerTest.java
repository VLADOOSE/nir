package com.vladoose.nir.integration;

import com.vladoose.nir.integration.goszakup.GoszakupImportScheduler;
import com.vladoose.nir.integration.skpharmacy.SkPharmacyImportScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TenderAutoImportSchedulerTest {

    final GoszakupImportScheduler goszakup = mock(GoszakupImportScheduler.class);
    final SkPharmacyImportScheduler sk = mock(SkPharmacyImportScheduler.class);

    List<CronTask> tasks(boolean enabled, String gzCron, String skCron, String zone) {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        new TenderAutoImportScheduler(goszakup, sk, enabled, gzCron, skCron, zone).configureTasks(registrar);
        return registrar.getCronTaskList();
    }

    /** Следующий запуск задачи после момента «от» — проверяет и выражение, и пояс. */
    static Instant next(CronTask task, String from) {
        return task.getTrigger().nextExecution(new SimpleTriggerContext(Clock.fixed(Instant.parse(from), ZoneOffset.UTC)));
    }

    @Test
    void disabled_registersNothing_evenWithBrokenCron() {
        assertThat(tasks(false, "мусор", "мусор", "Луна/Море")).isEmpty();
    }

    /** Пусто (в т. ч. пустая переменная окружения) — расписание по умолчанию, пояс — Уральск (UTC+5). */
    @Test
    void blank_defaults_goszakupHourly_skThriceADay_oralTime() {
        List<CronTask> t = tasks(true, "", " ", "");

        assertThat(t).extracting(CronTask::getExpression)
                .containsExactly(TenderAutoImportScheduler.DEFAULT_GOSZAKUP_CRON, TenderAutoImportScheduler.DEFAULT_SK_PHARMACY_CRON);
        // среда 2026-10-07, 08:00 по Уральску = 03:00Z → goszakup в 08:10, СК-Фармация в 08:40
        assertThat(next(t.get(0), "2026-10-07T03:00:00Z")).isEqualTo(Instant.parse("2026-10-07T03:10:00Z"));
        assertThat(next(t.get(1), "2026-10-07T03:00:00Z")).isEqualTo(Instant.parse("2026-10-07T03:40:00Z"));
        // суббота 19:30 по Уральску → goszakup только в понедельник 08:10 (воскресенье пропущено)
        assertThat(next(t.get(0), "2026-10-10T14:30:00Z")).isEqualTo(Instant.parse("2026-10-12T03:10:00Z"));
        // СК-Фармация: после 08:40 — 12:40
        assertThat(next(t.get(1), "2026-10-07T03:41:00Z")).isEqualTo(Instant.parse("2026-10-07T07:40:00Z"));
    }

    /** Каждое расписание запускает СВОЮ площадку: перепутанные задачи гнали бы СК-Фармацию ежечасно (≈ 900 запросов за прогон). */
    @Test
    void eachScheduleStartsItsOwnPlatform() {
        List<CronTask> t = tasks(true, "", "", "");

        t.get(0).getRunnable().run();
        verify(goszakup).startAsync(null);
        verifyNoInteractions(sk);

        t.get(1).getRunnable().run();
        verify(sk).startAsync();
    }

    @Test
    void dash_disablesThatPlatformOnly() {
        assertThat(tasks(true, "", "-", "")).extracting(CronTask::getExpression)
                .containsExactly(TenderAutoImportScheduler.DEFAULT_GOSZAKUP_CRON);
    }

    /** Опечатка в расписании выключает только эту площадку с ошибкой в логе — старт бэкенда не падает. */
    @Test
    void brokenCron_disablesThatPlatform_noException() {
        assertThat(tasks(true, "0 10 8-19 * *", "", "")).extracting(CronTask::getExpression)
                .containsExactly(TenderAutoImportScheduler.DEFAULT_SK_PHARMACY_CRON);
    }

    @Test
    void brokenZone_fallsBackToOral() {
        List<CronTask> t = tasks(true, "", "-", "Луна/Море");

        assertThat(next(t.get(0), "2026-10-07T03:00:00Z")).isEqualTo(Instant.parse("2026-10-07T03:10:00Z"));
    }

    @Test
    void ticks_startTheirPlatformInBackground() {
        TenderAutoImportScheduler s = new TenderAutoImportScheduler(goszakup, sk, true, "", "", "");

        s.tickGoszakup();
        verify(goszakup).startAsync(null);
        verifyNoInteractions(sk);

        s.tickSkPharmacy();
        verify(sk).startAsync();
    }
}
