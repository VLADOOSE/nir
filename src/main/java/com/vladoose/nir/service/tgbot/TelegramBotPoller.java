package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.integration.telegram.BotUpdate;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramException;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Приём нажатий бота (спека 2026-10-21-tender-telegram-bot §8): свой поток telegram-bot, long-poll getUpdates. При старте
 * накопившееся пропускается — нажатие часовой давности письмо не отправит. 409 (вебхук / другой читатель) — стоп с ERROR;
 * 401 / 404 — пауза 10 мин; 429 — retry_after; прочее — 5 с … 1 мин. Выключено по умолчанию (TELEGRAM_BOT_ACTIONS_ENABLED).
 */
@Component
public class TelegramBotPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotPoller.class);

    /** Пауза; InterruptedException — остановка цикла. */
    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    private final TelegramBotApi api;
    private final Consumer<BotUpdate> handler;
    private final boolean enabled;
    private final Sleeper sleeper;
    /** Перед первым опросом: сверка писем, оборванных посреди отправки ({@link BotLetterActions#recoverInterrupted}). */
    private final Runnable onStart;
    private volatile boolean running;
    private Thread thread;

    @Autowired
    public TelegramBotPoller(TelegramBotApi api, BotUpdateRouter router, BotLetterActions letters, TelegramSettings telegram,
                             @Value("${notify.telegram.bot-actions-enabled:false}") boolean botActions,
                             @Value("${product-watch.enabled:false}") boolean productWatch) {
        this(api, router::handle, botActions && productWatch && telegram.isConfigured(), Thread::sleep, letters::recoverInterrupted);
    }

    public TelegramBotPoller(TelegramBotApi api, Consumer<BotUpdate> handler, boolean enabled, Sleeper sleeper) {
        this(api, handler, enabled, sleeper, () -> { });
    }

    public TelegramBotPoller(TelegramBotApi api, Consumer<BotUpdate> handler, boolean enabled, Sleeper sleeper, Runnable onStart) {
        this.api = api;
        this.handler = handler;
        this.enabled = enabled;
        this.sleeper = sleeper;
        this.onStart = onStart;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!enabled || running) return;
        running = true;
        thread = new Thread(this::loop, "telegram-bot");
        thread.setDaemon(true);
        thread.start();
        log.info("Кнопки бота Telegram: приём нажатий включён");
    }

    @PreDestroy
    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    public void loop() {
        running = true;
        try {
            onStart.run();
        } catch (RuntimeException e) {
            log.warn("Кнопки бота Telegram: сверка при запуске не удалась ({}) — приём нажатий идёт", e.getClass().getSimpleName());
        }
        long offset = -1;
        long backoff = 0;
        while (running) {
            try {
                if (offset < 0) offset = skipPending();
                offset = pollOnce(offset);
                backoff = 0;
            } catch (TelegramException e) {
                if (e.status() == 409) {
                    log.error("Кнопки бота Telegram не работают: у бота установлен вебхук или его обновления читает другая программа");
                    running = false;
                    return;
                }
                long wait = e.status() == 401 || e.status() == 404 ? 600_000
                        : e.retryAfterSeconds() != null ? e.retryAfterSeconds() * 1000L
                        : (backoff = backoff == 0 ? 5_000 : Math.min(backoff * 2, 60_000));
                log.warn("Кнопки бота Telegram: {} — повтор через {} с", e.getMessage(), wait / 1000);
                if (!pause(wait)) return;
            } catch (RuntimeException e) {
                backoff = backoff == 0 ? 5_000 : Math.min(backoff * 2, 60_000);
                log.warn("Кнопки бота Telegram: сбой приёма ({}) — повтор через {} с", e.getClass().getSimpleName(), backoff / 1000);
                if (!pause(backoff)) return;
            }
        }
    }

    /** Накопившиеся без АИС обновления не выполняются (спека §8): offset — за последним; подтверждение — вторым запросом. */
    public long skipPending() {
        List<BotUpdate> last = api.getUpdates(-1, 0);
        long offset = last.isEmpty() ? 0 : last.get(last.size() - 1).updateId() + 1;
        if (offset > 0) api.getUpdates(offset, 0);
        return offset;
    }

    public long pollOnce(long offset) {
        long next = offset;
        for (BotUpdate u : api.getUpdates(offset, TelegramBotApi.POLL_SECONDS)) {
            next = Math.max(next, u.updateId() + 1);
            try {
                handler.accept(u);
            } catch (RuntimeException e) {
                log.warn("Кнопки бота Telegram: обновление {} не обработано: {}", u.updateId(), e.getClass().getSimpleName());
            }
        }
        return next;
    }

    private boolean pause(long ms) {
        try {
            sleeper.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
            return false;
        }
    }
}
