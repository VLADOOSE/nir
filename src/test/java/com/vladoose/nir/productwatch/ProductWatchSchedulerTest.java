package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.tendernotify.ImportOutageNotifier;
import org.junit.jupiter.api.Test;
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

    private ProductWatchScheduler scheduler(boolean enabled, String cron, String plans, String catalogCron) {
        return new ProductWatchScheduler(search, specs, rematch, catalog, outage, enabled, cron, plans, catalogCron, "", "10");
    }

    @Test
    void registration_defaultsDashTypo() {
        ScheduledTaskRegistrar r = new ScheduledTaskRegistrar();
        scheduler(true, "", "-", "0 0 6 * *").configureTasks(r);   // поиск — умолчание, планы — выкл, каталог — опечатка
        assertThat(r.getCronTaskList()).hasSize(1);
        ScheduledTaskRegistrar off = new ScheduledTaskRegistrar();
        scheduler(false, "", "", "").configureTasks(off);
        assertThat(off.getCronTaskList()).isEmpty();
    }

    @Test
    void searchRunsInOwnThreadUnderKz_thenSpecs_thenOutage() throws Exception {
        AtomicReference<Market> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> { seen.set(MarketContext.get()); return null; }).when(search).run(any());
        doAnswer(inv -> { done.countDown(); return null; }).when(outage).afterRun(any(), any(), any());
        ProductWatchScheduler s = scheduler(true, "", "", "");
        s.startSearch();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isEqualTo(Market.KZ);
        verify(specs).run(eq(10), any(ImportSummary.class));
        verify(outage).afterRun(eq("product-watch"), eq("Поиск нашей продукции"), any());
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

}
