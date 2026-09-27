package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LeadSyncStatusResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Расписание приёма заявок westmed.kz (спека §6.4). Работа — на СВОЁМ однопоточном экзекьюторе
 * «westmed-leads», а не на общем scheduling-1: зависший сайт не должен задерживать приём почты и импорты.
 * Рынок ставится ЯВНО и чистится в finally (§6 CLAUDE.md). После отказа входа — пауза authBackoffMs:
 * сайт пускает 5 входов за 5 минут с IP, и долбёжка каждые 90 с держала бы его в 429.
 */
@Component
public class WestmedLeadScheduler {

    private static final Logger log = LoggerFactory.getLogger(WestmedLeadScheduler.class);

    private final WestmedLeadSync sync;
    private final WestmedClient client;
    private final boolean enabled;
    private final boolean writeStatus;
    private final Market market;
    private final long authBackoffMs;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "westmed-leads");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile OffsetDateTime lastRunAt;
    private volatile OffsetDateTime lastSuccessAt;
    private volatile String lastError;
    private volatile int lastCreated;
    private volatile long authBlockedUntil;
    private volatile boolean credentialsWarned;

    public WestmedLeadScheduler(WestmedLeadSync sync, WestmedClient client,
                                @Value("${leads.westmed.enabled:false}") boolean enabled,
                                @Value("${leads.westmed.write-status:false}") boolean writeStatus,
                                @Value("${leads.westmed.market:KZ}") String market,
                                @Value("${leads.westmed.auth-backoff-ms:600000}") long authBackoffMs) {
        this.sync = sync;
        this.client = client;
        this.enabled = enabled;
        this.writeStatus = writeStatus;
        this.market = Market.fromHeader(market);
        this.authBackoffMs = authBackoffMs;
    }

    @Scheduled(fixedDelayString = "${leads.westmed.poll-ms:90000}",
               initialDelayString = "${leads.westmed.initial-delay-ms:30000}")
    public void tick() {
        if (enabled) submit();
    }

    /** «Проверить сейчас»: цикл в том же экзекьюторе, ждём до 30 с; идёт другой — отдаём текущее состояние. */
    public LeadSyncStatusResponse runNow() {
        if (!enabled) throw new BadRequestException("Приём заявок с сайта westmed.kz выключен");
        Future<?> f = submit();
        if (f != null) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // цикл продолжится в фоне — вернём то, что есть
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                // ошибки цикл сам пишет в lastError
            }
        }
        return status();
    }

    private Future<?> submit() {
        if (!running.compareAndSet(false, true)) return null;
        try {
            return executor.submit(this::cycle);
        } catch (RejectedExecutionException e) {
            running.set(false);
            return null;
        }
    }

    /** Один цикл. Пакетная видимость — ради тестов (там зовётся напрямую, в транзакции теста). */
    void cycle() {
        try {
            lastRunAt = OffsetDateTime.now();
            if (!client.isConfigured()) {
                lastError = "не заданы учётные данные сайта (WESTMED_USERNAME / WESTMED_PASSWORD)";
                if (!credentialsWarned) {
                    log.warn("westmed.kz: {}", lastError);
                    credentialsWarned = true;
                }
                return;
            }
            if (System.currentTimeMillis() < authBlockedUntil) return;   // пауза: lastError уже объясняет
            MarketContext.set(market);
            WestmedSyncResult r = sync.runOnce();
            lastCreated = r.created;
            lastSuccessAt = OffsetDateTime.now();
            lastError = r.errors > 0 ? "часть операций не удалась: " + r.lastError : null;
        } catch (WestmedAuthException e) {
            authBlockedUntil = System.currentTimeMillis() + authBackoffMs;
            lastError = e.getMessage() + " — повтор через " + Math.max(1, authBackoffMs / 60_000) + " мин";
            log.warn("westmed.kz: {}", lastError);
        } catch (RuntimeException e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("westmed.kz: цикл синхронизации не удался: {}", lastError);
        } finally {
            MarketContext.clear();
            running.set(false);
        }
    }

    public LeadSyncStatusResponse status() {
        LeadSyncStatusResponse s = new LeadSyncStatusResponse();
        s.setEnabled(enabled);
        s.setWriteStatus(writeStatus);
        s.setRunning(running.get());
        s.setLastRunAt(lastRunAt);
        s.setLastSuccessAt(lastSuccessAt);
        s.setLastError(lastError);
        s.setLastCreated(lastCreated);
        return s;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
