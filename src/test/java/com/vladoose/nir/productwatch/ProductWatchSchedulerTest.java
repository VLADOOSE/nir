package com.vladoose.nir.productwatch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.tendernotify.ImportOutageNotifier;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProductWatchSchedulerTest {

    ProductWatchService search = mock(ProductWatchService.class);
    ProductSpecChecker specs = mock(ProductSpecChecker.class);
    ProductRematchService rematch = mock(ProductRematchService.class);
    SiteCatalogSync catalog = mock(SiteCatalogSync.class);
    ImportOutageNotifier outage = mock(ImportOutageNotifier.class);
    ProductWatchNotifier notifier = mock(ProductWatchNotifier.class);
    ProcurementPlanService planService = mock(ProcurementPlanService.class);
    ProcurementPlanDigest planDigest = mock(ProcurementPlanDigest.class);

    private ProductWatchScheduler scheduler(boolean enabled, String cron, String plans, String catalogCron) {
        return new ProductWatchScheduler(search, specs, rematch, catalog, outage, notifier, planService, planDigest, enabled, cron, plans,
                catalogCron, "", "10");
    }

    @Test
    void registration_defaultsDashTypo() {
        ScheduledTaskRegistrar r = new ScheduledTaskRegistrar();
        scheduler(true, "", "-", "0 0 6 * *").configureTasks(r);   // поиск — умолчание, планы — выкл, каталог — опечатка
        assertThat(r.getCronTaskList()).hasSize(1);
        ScheduledTaskRegistrar all = new ScheduledTaskRegistrar();
        scheduler(true, "", "", "").configureTasks(all);             // поиск, планы, каталог — по умолчанию
        assertThat(all.getCronTaskList()).hasSize(3);
        ScheduledTaskRegistrar off = new ScheduledTaskRegistrar();
        scheduler(false, "", "", "").configureTasks(off);
        assertThat(off.getCronTaskList()).isEmpty();
    }

    @Test
    void searchRunsInOwnThreadUnderKz_thenSpecs_thenNotify_thenOutage() throws Exception {
        AtomicReference<Market> seen = new AtomicReference<>();
        AtomicReference<Market> atNotify = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> { seen.set(MarketContext.get()); return null; }).when(search).run(any());
        doAnswer(inv -> { atNotify.set(MarketContext.get()); return null; }).when(notifier).afterRun(any());
        doAnswer(inv -> { done.countDown(); return null; }).when(outage).afterRun(any(), any(), any());
        ProductWatchScheduler s = scheduler(true, "", "", "");
        s.startSearch();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isEqualTo(Market.KZ);
        assertThat(atNotify.get()).as("уведомление читает базу — под рынком KZ").isEqualTo(Market.KZ);
        var order = inOrder(search, specs, notifier, outage);
        order.verify(search).run(any());
        order.verify(specs).run(eq(10), any(ImportSummary.class));
        order.verify(notifier).afterRun(any(ImportSummary.class));
        order.verify(outage).afterRun(eq("product-watch"), eq("Поиск нашей продукции"), any());
    }

    /** Поиск упал целиком — найденное до сбоя всё равно уходит в Telegram, сбой уведомления итог не роняет. */
    @Test
    void searchFails_stillNotifies_notifyFailure_stillReports() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        doThrow(new IllegalStateException("boom")).when(search).run(any());
        doThrow(new IllegalStateException("Telegram")).when(notifier).afterRun(any());
        doAnswer(inv -> { done.countDown(); return null; }).when(outage).afterRun(any(), any(), any());
        scheduler(true, "", "", "").startSearch();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        verify(notifier).afterRun(any(ImportSummary.class));
    }

    @Test
    void disabled_noRun() {
        ProductWatchScheduler s = scheduler(false, "", "", "");
        assertThat(s.startSearch().enabled()).isFalse();
        verifyNoInteractions(search);
    }

    @Test
    void secondStartWhileQueued_notDuplicated() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> { release.await(5, TimeUnit.SECONDS); return null; }).when(search).run(any());
        ProductWatchScheduler s = scheduler(true, "", "", "");
        s.startSearch();
        s.startSearch();
        release.countDown();
        Thread.sleep(300);
        verify(search, times(1)).run(any());
    }

    @Test
    void plansRunCollectThenDigest_inOrder() throws Exception {
        when(planService.collect(any())).thenReturn(true);
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> { done.countDown(); return null; }).when(outage).afterRun(any(), any(), any());
        ProductWatchScheduler s = scheduler(true, "-", "-", "-");
        s.startPlans();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        InOrder o = inOrder(planService, planDigest, outage);
        o.verify(planService).collect(any());
        o.verify(planDigest).flush(eq(true), any());
        o.verify(outage).afterRun(eq("product-plans"), eq("Сбор планов закупок"), any());
    }

    /** Итог прогона — в лог (CLAUDE.md §14: по пустому логу удачный прогон неотличим от «не запускался»). */
    @Test
    void runSummaryGoesToLog_errorsAsWarn() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ProductWatchScheduler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            CountDownLatch done = new CountDownLatch(2);
            doAnswer(inv -> { ImportSummary s = inv.getArgument(0); s.setMessage("Найдено 7, наших 4, новых 4, обновлено 0, ошибок 0"); return null; })
                    .when(search).run(any());
            doAnswer(inv -> { ImportSummary s = inv.getArgument(0); s.addError("план 5"); s.setMessage("Планы: найдено 9, наших 2, новых 2, ошибок 1"); return true; })
                    .when(planService).collect(any());
            doAnswer(inv -> { done.countDown(); return null; }).when(outage).afterRun(any(), any(), any());
            ProductWatchScheduler s = scheduler(true, "-", "-", "-");
            s.startSearch();
            s.startPlans();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(logs.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.INFO);
                assertThat(e.getFormattedMessage()).isEqualTo("Поиск нашей продукции: Найдено 7, наших 4, новых 4, обновлено 0, ошибок 0");
            });
            assertThat(logs.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).isEqualTo("Сбор планов закупок: Планы: найдено 9, наших 2, новых 2, ошибок 1");
            });
        } finally {
            logger.detachAppender(logs);
        }
    }
}
