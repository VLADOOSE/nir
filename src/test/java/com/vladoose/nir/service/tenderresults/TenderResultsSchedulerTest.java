package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.market.MarketCollector;
import com.vladoose.nir.service.tendernotify.ImportOutageNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Расписание итогов (спека 2026-10-22-tender-results-market-prices §14, решение 11). */
class TenderResultsSchedulerTest {

    TenderResultsService results = mock(TenderResultsService.class);
    TenderResultsNotifier notifier = mock(TenderResultsNotifier.class);
    ImportOutageNotifier outage = mock(ImportOutageNotifier.class);
    MarketCollector market = mock(MarketCollector.class);

    private TenderResultsScheduler scheduler(boolean enabled, String cron, String digest, Duration wait) {
        return new TenderResultsScheduler(results, notifier, outage, market, enabled, cron, digest, "", "", wait);
    }

    @Test
    void registration_defaultsDashTypo() {
        ScheduledTaskRegistrar all = new ScheduledTaskRegistrar();
        scheduler(true, "", "", Duration.ofSeconds(1)).configureTasks(all);
        assertThat(all.getCronTaskList()).hasSize(3);
        ScheduledTaskRegistrar part = new ScheduledTaskRegistrar();
        scheduler(true, "0 50 8-20 * *", "-", Duration.ofSeconds(1)).configureTasks(part);   // опечатка и «-» — оба выключены
        assertThat(part.getCronTaskList()).as("опечатка и «-» выключены, сбор цен — по умолчанию").hasSize(1);
        ScheduledTaskRegistrar off = new ScheduledTaskRegistrar();
        scheduler(false, "", "", Duration.ofSeconds(1)).configureTasks(off);
        assertThat(off.getCronTaskList()).isEmpty();
    }

    @Test
    void results_ownThreadUnderKz_thenNotify_thenOutage() throws Exception {
        AtomicReference<Market> market = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> {
            market.set(MarketContext.get());
            thread.set(Thread.currentThread().getName());
            return null;
        }).when(results).run(any());
        doAnswer(inv -> { done.countDown(); return null; }).when(outage).afterProductRun(any(), any(), any());
        scheduler(true, "", "", Duration.ofSeconds(1)).startResults();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(market.get()).isEqualTo(Market.KZ);
        assertThat(thread.get()).isEqualTo("tender-results");
        var order = inOrder(results, notifier, outage);
        order.verify(results).run(any(ImportSummary.class));
        order.verify(notifier).flushUrgent(any(ImportSummary.class));
        order.verify(outage).afterProductRun(eq("tender-results"), eq("Проверка итогов тендеров"), any());
    }

    /** Проход упал — найденное до сбоя всё равно уходит, итог — в оповещатель сбоев. */
    @Test
    void resultsFail_stillNotifyAndReport() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        doThrow(new IllegalStateException("boom")).when(results).run(any());
        doAnswer(inv -> { done.countDown(); return null; }).when(outage).afterProductRun(any(), any(), any());
        scheduler(true, "", "", Duration.ofSeconds(1)).startResults();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        verify(notifier).flushUrgent(any());
    }

    @Test
    void check_waitsForPass_thenSends() {
        doAnswer(inv -> {
            ImportSummary s = inv.getArgument(1);
            s.setMessage("Итоги: тендеров проверено 1, событий 2");
            return null;
        }).when(results).check(eq(5L), any());
        TenderResultsScheduler.CheckResult r = scheduler(false, "", "", Duration.ofSeconds(5)).check(5L);
        assertThat(r.done()).isTrue();
        assertThat(r.ok()).isTrue();
        assertThat(r.message()).isEqualTo("Итоги: тендеров проверено 1, событий 2");
        verify(results).checkable(5L);
        verify(notifier).flushUrgent(any());
    }

    /** Не успело за отведённое время — «проверка идёт» (решение 11). */
    @Test
    void check_slow_returnsPending() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> { release.await(5, TimeUnit.SECONDS); return null; }).when(results).check(eq(6L), any());
        TenderResultsScheduler.CheckResult r = scheduler(true, "", "", Duration.ofMillis(200)).check(6L);
        release.countDown();
        assertThat(r.done()).isFalse();
        assertThat(r.message()).contains("идёт");
    }

    @Test
    void check_notCheckable_400_beforeQueue() {
        doThrow(new BadRequestException("Приём заявок ещё идёт — итогов пока нет")).when(results).checkable(7L);
        assertThatThrownBy(() -> scheduler(true, "", "", Duration.ofSeconds(1)).check(7L)).isInstanceOf(BadRequestException.class);
        verify(results, never()).check(anyLong(), any());
    }

    @Test
    void digest_runsNotifier_disabledDoesNothing() {
        scheduler(true, "", "", Duration.ofSeconds(1)).startDigest();
        verify(notifier, timeout(2000)).digest(any());
        TenderResultsScheduler off = scheduler(false, "", "", Duration.ofSeconds(1));
        off.startResults();
        verify(results, after(300).never()).run(any());
        assertThat(off.status().enabled()).isFalse();
    }

    @Test
    void market_runsCollector_reportsOwnKey() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> { done.countDown(); return null; }).when(outage).afterProductRun(eq("market-prices"), any(), any());
        scheduler(true, "", "", Duration.ofSeconds(1)).startMarket();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        verify(market).collectDaily(any());
        verify(outage).afterProductRun(eq("market-prices"), eq("Сбор цен рынка"), any());
    }
}
