package com.vladoose.nir.service.mail;

import com.vladoose.nir.integration.telegram.TelegramClient;
import com.vladoose.nir.integration.telegram.TelegramException;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Отправка очереди уведомлений о письмах (спека §5.3). Пачка до 20 по возрастанию id; ПЕРВЫЙ сбой останавливает пачку
 * до следующего прохода — сбои Telegram почти всегда общие (токен, чат, тема, сеть), а долбёжка упирается в лимит.
 * 429 — пауза до retry_after. Не ушло за сутки от постановки — FAILED (письмо остаётся во «Входящих» и в почте).
 */
@Service
public class MailTelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailTelegramNotifier.class);
    static final int BATCH = 20;
    static final Duration GIVE_UP = Duration.ofHours(24);

    public record FlushResult(int sent, long pending, String lastError) {}

    private final MailNotifyStore store;
    private final TelegramClient client;
    private final TelegramSettings settings;
    private final Clock clock;
    private volatile Instant pausedUntil = Instant.EPOCH;
    private volatile String lastError;

    @Autowired
    public MailTelegramNotifier(MailNotifyStore store, TelegramClient client, TelegramSettings settings) {
        this(store, client, settings, Clock.systemUTC());
    }

    MailTelegramNotifier(MailNotifyStore store, TelegramClient client, TelegramSettings settings, Clock clock) {
        this.store = store;
        this.client = client;
        this.settings = settings;
        this.clock = clock;
    }

    public FlushResult flush() {
        if (!settings.isConfigured()) return new FlushResult(0, 0, null);
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) return new FlushResult(0, store.countPending(), lastError);
        int sent = 0;
        for (PendingNotification p : store.pending(BATCH)) {
            if (p.queuedAt() != null && p.queuedAt().toInstant().plus(GIVE_UP).isBefore(now)) {
                store.markFailed(p.id(), "не отправлено за сутки" + (lastError == null ? "" : ": " + lastError));
                log.warn("Уведомление о письме id={} не ушло в Telegram за сутки — снято с очереди", p.id());
                continue;
            }
            try {
                client.sendMail(p.text(), p.silent());
                store.markSent(p.id(), OffsetDateTime.now(clock));
                sent++;
                lastError = null;
            } catch (TelegramException e) {
                lastError = e.getMessage();
                store.markAttempt(p.id(), e.getMessage());
                // retry_after отсчитывается от ответа, а не от начала пачки: отправки до него могли занять секунды
                if (e.retryAfterSeconds() != null) pausedUntil = clock.instant().plusSeconds(e.retryAfterSeconds());
                log.warn("Уведомление о письме id={} не отправлено: {}", p.id(), e.getMessage());
                break;
            } catch (RuntimeException e) {        // дефект у нас — класс, без текста (в нём бывают адреса)
                lastError = "внутренняя ошибка (" + e.getClass().getSimpleName() + ")";
                store.markAttempt(p.id(), lastError);
                log.warn("Уведомление о письме id={} не отправлено: {}", p.id(), lastError);
                break;
            }
        }
        return new FlushResult(sent, store.countPending(), lastError);
    }
}
