package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.WhatsappStatusResponse;
import com.vladoose.nir.entity.Market;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Приём WhatsApp (спека whatsapp-chats §6.1): тик раз в секунду ставит проход в СВОЙ однопоточный экзекьютор
 * «whatsapp-chats» — long-poll держит поток до 20 с, общий scheduling-1 занимать нельзя. Рынок ставится ЯВНО
 * и чистится в finally (§6 CLAUDE.md). Паузы: отказ ключа — 10 мин, прочие сбои — 30 с.
 */
@Component
public class WhatsappChatScheduler {

    private static final Logger log = LoggerFactory.getLogger(WhatsappChatScheduler.class);
    static final int MAX_PER_DRAIN = 200;

    private final WhatsappChatSync sync;
    private final GreenApiClient client;
    private final WhatsappStatusHolder status;
    private final boolean enabled;
    private final Market market;
    private final long authBackoffMs;
    private final long errorBackoffMs;
    private final long stateRefreshMs;
    private final long settingsRefreshMs;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "whatsapp-chats");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long pausedUntil;
    private volatile long nextStateCheck;
    private volatile long nextSettingsCheck;
    private volatile boolean credentialsWarned;

    public WhatsappChatScheduler(WhatsappChatSync sync, GreenApiClient client, WhatsappStatusHolder status,
                                 @Value("${chats.whatsapp.enabled:false}") boolean enabled,
                                 @Value("${chats.whatsapp.market:KZ}") String market,
                                 @Value("${chats.whatsapp.auth-backoff-ms:600000}") long authBackoffMs,
                                 @Value("${chats.whatsapp.error-backoff-ms:30000}") long errorBackoffMs,
                                 @Value("${chats.whatsapp.state-refresh-ms:300000}") long stateRefreshMs,
                                 @Value("${chats.whatsapp.settings-refresh-ms:3600000}") long settingsRefreshMs) {
        this.sync = sync;
        this.client = client;
        this.status = status;
        this.enabled = enabled;
        this.market = Market.fromHeader(market);
        this.authBackoffMs = authBackoffMs;
        this.errorBackoffMs = errorBackoffMs;
        this.stateRefreshMs = stateRefreshMs;
        this.settingsRefreshMs = settingsRefreshMs;
    }

    @Scheduled(fixedDelayString = "${chats.whatsapp.tick-ms:1000}",
               initialDelayString = "${chats.whatsapp.initial-delay-ms:20000}")
    public void tick() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            executor.submit(() -> {
                try {
                    cycle();
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            running.set(false);
        }
    }

    /** Один проход. Пакетная видимость — ради тестов (зовётся напрямую, в транзакции теста). */
    void cycle() {
        if (!client.isConfigured()) {
            status.setLastError("не заданы учётные данные Green-API (WHATSAPP_API_URL / WHATSAPP_ID_INSTANCE / WHATSAPP_API_TOKEN)");
            if (!credentialsWarned) {
                log.warn("WhatsApp: {}", status.lastError());
                credentialsWarned = true;
            }
            return;
        }
        if (System.currentTimeMillis() < pausedUntil) return;   // пауза: lastError уже объясняет
        MarketContext.set(market);
        try {
            long now = System.currentTimeMillis();
            if (now >= nextSettingsCheck) {
                status.settingsChecked(client.settings());
                nextSettingsCheck = now + settingsRefreshMs;
            }
            if (now >= nextStateCheck) {
                status.setState(client.state());
                nextStateCheck = now + stateRefreshMs;
            }
            if (sync.drain(MAX_PER_DRAIN)) {
                status.setLastError(null);
            } else {
                pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            }
        } catch (GreenApiAuthException e) {
            pausedUntil = System.currentTimeMillis() + authBackoffMs;
            status.setLastError(e.getMessage() + " — повтор через " + Math.max(1, authBackoffMs / 60_000) + " мин");
            log.warn("WhatsApp: {}", status.lastError());
        } catch (GreenApiQuotaException e) {
            status.quotaExceeded();
            pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            status.setLastError(e.getMessage());
        } catch (RuntimeException e) {
            pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            status.setLastError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.warn("WhatsApp: проход приёма не удался: {}", status.lastError());
        } finally {
            MarketContext.clear();
        }
    }

    public WhatsappStatusResponse status() {
        return status.snapshot(enabled, client.isConfigured());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
