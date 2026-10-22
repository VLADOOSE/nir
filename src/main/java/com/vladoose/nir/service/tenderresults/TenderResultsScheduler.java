package com.vladoose.nir.service.tenderresults;

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
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Итоги тендеров по расписанию (спека 2026-10-22-tender-results-market-prices §14): свой однопоточный исполнитель
 * `tender-results` — проходы, сводка и «Проверить итоги» идут по очереди; рынок KZ — в потоке (§6 CLAUDE.md). Расписания —
 * в коде ({@link CronSchedules}); выключатель TENDER_RESULTS_ENABLED (по умолчанию выключено). «Проверить итоги» работает и
 * при выключенном расписании (нажатие администратора).
 */
@Component
public class TenderResultsScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(TenderResultsScheduler.class);

    static final String DEFAULT_RESULTS_CRON = "0 50 8-20 * * MON-SAT";
    static final String DEFAULT_DIGEST_CRON = "0 0 9 * * MON-SAT";
    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Oral");
    static final Duration CHECK_WAIT = Duration.ofSeconds(60);

    public record Status(boolean enabled, boolean running, String current, Instant lastResultsAt, ImportSummary lastResults,
                         Instant lastDigestAt) {}

    /** «Проверить итоги»: done — проверка закончилась (иначе идёт в фоне), ok — без ошибок. */
    public record CheckResult(boolean done, boolean ok, String message) {}

    private final TenderResultsService results;
    private final TenderResultsNotifier notifier;
    private final ImportOutageNotifier outage;
    private final boolean enabled;
    private final String resultsCron;
    private final String digestCron;
    private final String zone;
    private final Duration checkWait;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tender-results");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean resultsQueued = new AtomicBoolean(false);
    private volatile String current;
    private volatile Instant lastResultsAt;
    private volatile Instant lastDigestAt;
    private volatile ImportSummary lastResults;

    @Autowired
    public TenderResultsScheduler(TenderResultsService results, TenderResultsNotifier notifier, ImportOutageNotifier outage,
                                  @Value("${tender-results.enabled:false}") boolean enabled,
                                  @Value("${tender-results.cron:}") String resultsCron,
                                  @Value("${tender-results.digest-cron:}") String digestCron,
                                  @Value("${tender-results.zone:}") String zone) {
        this(results, notifier, outage, enabled, resultsCron, digestCron, zone, CHECK_WAIT);
    }

    TenderResultsScheduler(TenderResultsService results, TenderResultsNotifier notifier, ImportOutageNotifier outage, boolean enabled,
                           String resultsCron, String digestCron, String zone, Duration checkWait) {
        this.results = results;
        this.notifier = notifier;
        this.outage = outage;
        this.enabled = enabled;
        this.resultsCron = resultsCron;
        this.digestCron = digestCron;
        this.zone = zone;
        this.checkWait = checkWait;
    }

    public boolean enabled() {
        return enabled;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!enabled) return;
        ZoneId z = CronSchedules.zone(zone, DEFAULT_ZONE, log);
        CronSchedules.register(registrar, "Итоги тендеров", resultsCron, DEFAULT_RESULTS_CRON, z, this::startResults, log);
        CronSchedules.register(registrar, "Сводка итогов тендеров", digestCron, DEFAULT_DIGEST_CRON, z, this::startDigest, log);
    }

    public Status status() {
        return new Status(enabled, current != null, current, lastResultsAt, lastResults, lastDigestAt);
    }

    public Status startResults() {
        if (!enabled || !resultsQueued.compareAndSet(false, true)) return status();
        ImportSummary sum = new ImportSummary();
        lastResults = sum;
        executor.execute(() -> runTask("итоги тендеров", () -> {
            try {
                results.run(sum);
            } finally {
                lastResultsAt = Instant.now();
                resultsQueued.set(false);
                notifyUrgent(sum);   // и после сбоя: записанное до него уже ждёт отправки
                report(sum);
            }
        }));
        return status();
    }

    public void startDigest() {
        if (!enabled) return;
        executor.execute(() -> runTask("сводка итогов", () -> {
            ImportSummary sum = new ImportSummary();
            try {
                notifier.digest(sum);
            } finally {
                lastDigestAt = Instant.now();
                if (sum.getErrors() > 0) log.warn("Сводка итогов тендеров: {}", sum.getLastError());
            }
        }));
    }

    /** «Проверить итоги» (решение 11): проверка допустимости — сразу (400), сама проверка — в очереди потока, ждём до 60 с. */
    public CheckResult check(long tenderId) {
        results.checkable(tenderId);
        ImportSummary sum = new ImportSummary();
        Future<?> f = executor.submit(() -> runTask("проверка тендера", () -> {
            try {
                results.check(tenderId, sum);
            } catch (RuntimeException e) {
                sum.addError(ErrorText.of(e));
            } finally {
                notifyUrgent(sum);
            }
        }));
        try {
            f.get(checkWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new CheckResult(false, false, "Проверка идёт — обновите карточку через минуту");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CheckResult(false, false, "Проверка прервана");
        } catch (ExecutionException e) {
            return new CheckResult(true, false, "Проверка упала: " + ErrorText.of(e.getCause()));
        }
        String message = sum.getMessage() != null ? sum.getMessage() : sum.getLastError();
        return new CheckResult(true, sum.isEnabled() && sum.getErrors() == 0, message);
    }

    /** Рынок — в потоке; Error не тонет в Future (execute). */
    private void runTask(String name, Runnable body) {
        MarketContext.set(Market.KZ);
        current = name;
        try {
            body.run();
        } catch (Throwable e) {
            log.error("Итоги тендеров: {} — прерван: {}", name, ErrorText.of(e));
        } finally {
            current = null;
            MarketContext.clear();
        }
    }

    private void notifyUrgent(ImportSummary sum) {
        try {
            notifier.flushUrgent(sum);
        } catch (Throwable e) {
            log.warn("Итоги тендеров: отправка в Telegram упала: {}", ErrorText.of(e));
        }
    }

    /** Итог прохода — в лог (успешный тоже, CLAUDE.md §14) и в оповещатель сбоев. */
    private void report(ImportSummary sum) {
        if (sum.getMessage() != null) {
            if (sum.getErrors() > 0) log.warn("Проверка итогов тендеров: {}", sum.getMessage());
            else log.info("Проверка итогов тендеров: {}", sum.getMessage());
        }
        try {
            outage.afterProductRun("tender-results", "Проверка итогов тендеров", sum);
        } catch (Throwable e) {
            log.warn("Итоги тендеров: оповещение о сбое упало: {}", ErrorText.of(e));
        }
    }
}
