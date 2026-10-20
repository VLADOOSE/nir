package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.CronSchedules;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.tendernotify.ImportOutageNotifier;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Прогоны «нашей продукции» (спека 2026-10-19-product-watch §7, §10, §6): один исполнитель `product-watch` — поиск,
 * планы, каталог и пересчёт идут по очереди и не нагружают токен одновременно; рынок KZ — в потоке прогона (§6 CLAUDE.md).
 * Расписания — в коде ({@link CronSchedules}); выключатель PRODUCT_WATCH_ENABLED (по умолчанию выключено).
 */
@Component
public class ProductWatchScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ProductWatchScheduler.class);

    static final String DEFAULT_SEARCH_CRON = "0 30 8-19 * * MON-SAT";
    static final String DEFAULT_PLANS_CRON = "0 30 7 * * MON-SAT";
    static final String DEFAULT_CATALOG_CRON = "0 0 6 * * *";
    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Oral");
    static final int DEFAULT_SPEC_PER_RUN = 10;

    public record Status(boolean enabled, boolean running, String current, Instant lastSearchAt, ImportSummary lastSearch,
                         Instant lastPlansAt, ImportSummary lastPlans, Instant lastCatalogAt, String lastCatalog) {}

    private final ProductWatchService search;
    private final ProductSpecChecker specs;
    private final ProductRematchService rematch;
    private final SiteCatalogSync catalog;
    private final ImportOutageNotifier outage;
    private final boolean enabled;
    private final String searchCron, plansCron, catalogCron, zone;
    private final int specPerRun;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "product-watch");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean searchQueued = new AtomicBoolean(false);
    private final AtomicBoolean catalogQueued = new AtomicBoolean(false);
    private final AtomicBoolean rematchQueued = new AtomicBoolean(false);
    private volatile String current;
    private volatile Instant lastSearchAt, lastPlansAt, lastCatalogAt;
    private volatile ImportSummary lastSearch, lastPlans;
    private volatile String lastCatalog;

    @Autowired
    public ProductWatchScheduler(ProductWatchService search, ProductSpecChecker specs, ProductRematchService rematch,
                                 SiteCatalogSync catalog, ImportOutageNotifier outage,
                                 @Value("${product-watch.enabled:false}") boolean enabled,
                                 @Value("${product-watch.cron:}") String searchCron,
                                 @Value("${product-watch.plans-cron:}") String plansCron,
                                 @Value("${product-watch.catalog-cron:}") String catalogCron,
                                 @Value("${product-watch.zone:}") String zone,
                                 @Value("${product-watch.spec-per-run:}") String specPerRun) {
        this.search = search; this.specs = specs; this.rematch = rematch; this.catalog = catalog; this.outage = outage;
        this.enabled = enabled; this.searchCron = searchCron; this.plansCron = plansCron; this.catalogCron = catalogCron;
        this.zone = zone; this.specPerRun = parseInt(specPerRun, DEFAULT_SPEC_PER_RUN);
    }

    private static int parseInt(String s, int byDefault) {
        try {
            return s == null || s.isBlank() ? byDefault : Math.max(0, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return byDefault;
        }
    }

    public boolean enabled() { return enabled; }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!enabled) return;
        ZoneId z = CronSchedules.zone(zone, DEFAULT_ZONE, log);
        CronSchedules.register(registrar, "Поиск нашей продукции", searchCron, DEFAULT_SEARCH_CRON, z, this::startSearch, log);
        CronSchedules.register(registrar, "Каталог сайта", catalogCron, DEFAULT_CATALOG_CRON, z, this::startCatalog, log);
        // планы закупок — задача 11: CronSchedules.register(registrar, "Планы закупок", plansCron, DEFAULT_PLANS_CRON, z, this::startPlans, log);
    }

    /** При старте с включённым блоком — молчаливый пересчёт (каталог пуст — сперва загрузка). */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) return;
        startCatalog();
        startRematch();
    }

    public Status status() {
        return new Status(enabled, current != null, current, lastSearchAt, lastSearch, lastPlansAt, lastPlans, lastCatalogAt, lastCatalog);
    }

    public Status startSearch() {
        if (!enabled || !searchQueued.compareAndSet(false, true)) return status();
        ImportSummary sum = new ImportSummary();
        lastSearch = sum;
        executor.execute(() -> runTask("поиск по стране", () -> {
            try {
                search.run(sum);
                specs.run(specPerRun, sum);
            } finally {
                lastSearchAt = Instant.now();
                searchQueued.set(false);
                report(sum);
            }
        }));
        return status();
    }

    public Status startCatalog() {
        if (!enabled || !catalogQueued.compareAndSet(false, true)) return status();
        executor.execute(() -> runTask("каталог сайта", () -> {
            try {
                SiteCatalogSync.SyncResult r = catalog.sync();
                lastCatalog = "товаров " + r.count() + " (новых " + r.added() + ", изменено " + r.updated() + ", пропало " + r.removed() + ")"
                        + (r.full() ? "" : " — без описаний (публичный список)");
            } catch (RuntimeException e) {
                lastCatalog = "не загрузился: " + ErrorText.of(e);
            } finally {
                lastCatalogAt = Instant.now();
                catalogQueued.set(false);
            }
        }));
        return status();
    }

    public Status startRematch() {
        if (!enabled || !rematchQueued.compareAndSet(false, true)) return status();
        executor.execute(() -> runTask("пересчёт отметок", () -> {
            try {
                int n = rematch.rematchOpen();
                log.info("Наша продукция: пересчёт — тендеров с отметками {}", n);
            } finally {
                rematchQueued.set(false);
            }
        }));
        return status();
    }

    /** Рынок — в потоке прогона; Error не тонет в Future (execute, а не submit). */
    private void runTask(String name, Runnable body) {
        MarketContext.set(Market.KZ);
        current = name;
        try {
            body.run();
        } catch (Throwable e) {
            log.error("Наша продукция: {} — прерван: {}", name, ErrorText.of(e));
        } finally {
            current = null;
            MarketContext.clear();
        }
    }

    private void report(ImportSummary sum) {
        try {
            outage.afterRun("product-watch", "Поиск нашей продукции", sum);
        } catch (Throwable e) {
            log.warn("Наша продукция: оповещение о сбое упало: {}", ErrorText.of(e));
        }
    }
}
