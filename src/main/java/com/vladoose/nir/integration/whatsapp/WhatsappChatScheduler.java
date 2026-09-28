package com.vladoose.nir.integration.whatsapp;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.WhatsappStatusResponse;
import com.vladoose.nir.entity.Market;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Приём WhatsApp (спеки whatsapp-chats §6.1, whatsapp-waha §3): тик раз в секунду ставит проход в СВОЙ однопоточный
 * экзекьютор «whatsapp-chats» — long-poll Green-API держит поток до 20 с, общий scheduling-1 занимать нельзя. Рынок
 * ставится ЯВНО и чистится в finally (§6 CLAUDE.md). Проход: обслуживание источника (состояние, номер; у WAHA ещё
 * догонка и уборка) → очередь. Паузы: отказ ключа — 10 мин, прочие сбои — 30 с.
 */
@Component
public class WhatsappChatScheduler {

    private static final Logger log = LoggerFactory.getLogger(WhatsappChatScheduler.class);
    static final int MAX_PER_DRAIN = 200;

    private final WhatsappChatSync sync;
    private final WhatsappSource source;
    private final WhatsappStatusHolder status;
    private final boolean enabled;
    /** null — WHATSAPP_MARKET не распознан: приём стоит (раньше опечатка молча превращалась в RF). */
    private final Market market;
    private final String marketRaw;
    private final long authBackoffMs;
    private final long errorBackoffMs;
    /** Проход без продвижения дольше этого — строка состояния говорит «приём не продвигается». */
    private final long stallMs;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "whatsapp-chats");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long pausedUntil;
    private volatile boolean credentialsWarned;
    private volatile boolean marketWarned;

    public WhatsappChatScheduler(WhatsappChatSync sync, WhatsappSource source, WhatsappStatusHolder status,
                                 @Value("${chats.whatsapp.enabled:false}") boolean enabled,
                                 @Value("${chats.whatsapp.market:KZ}") String market,
                                 @Value("${chats.whatsapp.auth-backoff-ms:600000}") long authBackoffMs,
                                 @Value("${chats.whatsapp.error-backoff-ms:30000}") long errorBackoffMs,
                                 @Value("${chats.whatsapp.stall-ms:300000}") long stallMs) {
        this.sync = sync;
        this.source = source;
        this.status = status;
        this.enabled = enabled;
        this.market = parseMarket(market);
        this.marketRaw = market;
        this.authBackoffMs = authBackoffMs;
        this.errorBackoffMs = errorBackoffMs;
        this.stallMs = stallMs;
    }

    static Market parseMarket(String raw) {
        if (raw == null) return null;
        try {
            return Market.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        status.progress();
        if (market == null) {
            status.setLastError("WHATSAPP_MARKET: неизвестный рынок «" + marketRaw + "» — приём остановлен (нужно KZ или RF)");
            if (!marketWarned) {
                log.error("WhatsApp: {}", status.lastError());
                marketWarned = true;
            }
            return;
        }
        if (!source.isConfigured()) {
            status.setLastError(source.configHint());
            if (!credentialsWarned) {
                log.warn("WhatsApp: {}", status.lastError());
                credentialsWarned = true;
            }
            return;
        }
        if (System.currentTimeMillis() < pausedUntil) return;   // пауза: lastError уже объясняет
        MarketContext.set(market);
        try {
            source.housekeeping(status);
            if (sync.drain(MAX_PER_DRAIN)) {
                status.setLastError(null);
            } else {
                pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            }
        } catch (GatewayAuthException e) {
            pausedUntil = System.currentTimeMillis() + authBackoffMs;
            if (changed(e.getMessage() + " — повтор через " + Math.max(1, authBackoffMs / 60_000) + " мин")) {
                log.warn("WhatsApp: {}", status.lastError());
            }
        } catch (GatewayQuotaException e) {
            status.quotaExceeded();
            pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            changed(e.getMessage());
        } catch (GatewayException e) {
            // свой текст без адреса; трасса ни о чём не скажет — сеть или ответ шлюза
            pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            if (changed(e.getMessage())) log.warn("WhatsApp: {} — пауза {} с", e.getMessage(), errorBackoffMs / 1000);
        } catch (RuntimeException e) {
            // в строку состояния (её видит любой вошедший) — только класс, подробности — в лог
            pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            if (changed(WhatsappChatSync.reason(e))) {
                log.warn("WhatsApp: проход приёма не удался — пауза {} с", errorBackoffMs / 1000, e);
            }
        } catch (Error e) {
            // нехватка памяти и т.п.: без паузы та же голова очереди повторялась бы раз в секунду, молча
            pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            if (changed(WhatsappChatSync.reason(e))) {
                log.error("WhatsApp: проход приёма упал ({}) — пауза {} с", e.getClass().getSimpleName(), errorBackoffMs / 1000, e);
            }
        } finally {
            MarketContext.clear();
        }
    }

    /** Записать ошибку в строку состояния; true — текст новый (в лог — один раз на состояние, не каждым проходом). */
    private boolean changed(String error) {
        boolean isNew = !Objects.equals(status.lastError(), error);
        status.setLastError(error);
        return isNew;
    }

    public WhatsappStatusResponse status() {
        WhatsappStatusResponse r = status.snapshot(enabled, source.isConfigured());
        r.setProvider(source.name());
        r.setMarket(market == null ? null : market.name());
        long idleMs = status.sinceProgressMs();
        if (running.get() && idleMs > stallMs) {
            r.setLastError("приём не продвигается " + Math.max(1, idleMs / 60_000) + " мин — перезапустите бэкенд"
                    + " (docker compose restart ais-backend), подробности в логе сервера");
        }
        return r;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
