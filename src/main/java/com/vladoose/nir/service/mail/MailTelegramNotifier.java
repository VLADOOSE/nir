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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * Отправка очереди уведомлений о письмах (спека §5.3) — по возрастанию id; ПЕРВЫЙ сбой останавливает пачку до
 * следующего прохода: сбои Telegram почти всегда общие (токен, чат, тема, сеть), а долбёжка упирается в лимит.
 * Не ушло за сутки от постановки — FAILED (письмо остаётся во «Входящих» и в почте).
 * <p>
 * <b>Предел отправок:</b> не больше {@value #PER_WINDOW} сообщений за скользящие 60 с и не чаще одного в 1,1 с. Бот
 * (@West_Med_bot) и группа «Заявки» — общие с сайтом westmed.kz, а сайт при любом отказе Telegram, 429 включительно,
 * своё уведомление о заявке выбрасывает без повтора. Telegram пускает в группу около 20 сообщений в минуту и не
 * больше одного в секунду: пачка из 20 писем подряд (догонка после простоя) выбрала бы лимит группы целиком, и заявка
 * с сайта в эту минуту пропала бы. Половина минутного лимита остаётся сайту. Дошли до предела — пачка кончается,
 * остаток уходит следующими проходами. Состояние предела живёт в бине: проходы идут по одному (поток mail-imap), и
 * ручной проход «Проверить почту» расходует тот же предел.
 * <p>
 * <b>Пауза после сбоя:</b> 429 — до retry_after из ответа. Прочие сбои подряд — нарастающая пауза: n-й → 1 мин ×
 * 2^(n−1), не больше 30 мин. Иначе сообщение, которое Telegram доставил, но ответил позже дедлайна клиента (15 с),
 * повторялось бы раз в минуту целые сутки — дублями в группе. Успешная отправка обнуляет счёт.
 */
@Service
public class MailTelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailTelegramNotifier.class);
    /** Выборка из очереди за проход: просроченные (старше суток) снимаются и сверх предела отправок. */
    static final int BATCH = 20;
    static final int PER_WINDOW = 10;
    static final Duration WINDOW = Duration.ofSeconds(60);
    static final Duration GAP = Duration.ofMillis(1100);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    static final Duration GIVE_UP = Duration.ofHours(24);

    public record FlushResult(int sent, long pending, String lastError) {}

    /** Пауза между отправками. В работе — сон потока; в тестах — запись запрошенного без сна. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final MailNotifyStore store;
    private final TelegramClient client;
    private final TelegramSettings settings;
    private final Clock clock;
    private final Sleeper sleeper;
    // Состояние — только внутри synchronized flush(): проходы и так идут по одному, но предел от этого не зависит.
    private Instant pausedUntil = Instant.EPOCH;
    private String lastError;
    private int failuresInRow;
    /** Попытки отправки за последние {@link #WINDOW}, по времени; последняя — отсчёт паузы {@link #GAP}. */
    private final Deque<Instant> recent = new ArrayDeque<>();

    @Autowired
    public MailTelegramNotifier(MailNotifyStore store, TelegramClient client, TelegramSettings settings) {
        this(store, client, settings, Clock.systemUTC(), d -> TimeUnit.NANOSECONDS.sleep(d.toNanos()));
    }

    MailTelegramNotifier(MailNotifyStore store, TelegramClient client, TelegramSettings settings, Clock clock,
                         Sleeper sleeper) {
        this.store = store;
        this.client = client;
        this.settings = settings;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public synchronized FlushResult flush() {
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
            if (!slotFree()) {
                log.info("Telegram: предел {} сообщений за {} с выбран — остальные уведомления уйдут следующими проходами",
                        PER_WINDOW, WINDOW.toSeconds());
                break;
            }
            if (!waitGap()) break;                       // поток останавливают — пачку закончить
            try {
                send(p);
                store.markSent(p.id(), OffsetDateTime.now(clock));
                sent++;
                lastError = null;
                failuresInRow = 0;
            } catch (TelegramException e) {
                lastError = e.getMessage();
                pauseAfterFailure(e.retryAfterSeconds());
                store.markAttempt(p.id(), e.getMessage());
                log.warn("Уведомление о письме id={} не отправлено: {}", p.id(), e.getMessage());
                break;
            } catch (RuntimeException e) {        // дефект у нас — класс, без текста (в нём бывают адреса)
                lastError = "внутренняя ошибка (" + e.getClass().getSimpleName() + ")";
                pauseAfterFailure(null);
                store.markAttempt(p.id(), lastError);
                log.warn("Уведомление о письме id={} не отправлено: {}", p.id(), lastError);
                break;
            }
        }
        return new FlushResult(sent, store.countPending(), lastError);
    }

    /** n-й сбой подряд (кроме 429) → пауза 1 мин × 2^(n−1), не больше {@link #MAX_BACKOFF}. */
    static Duration backoff(int failuresInRow) {
        long minutes = 1L << Math.min(Math.max(failuresInRow, 1) - 1, 5);
        return Duration.ofMinutes(Math.min(minutes, MAX_BACKOFF.toMinutes()));
    }

    /**
     * Попытка отправки — и неудачная расходует предел: запрос мог дойти до Telegram (ответ не дождались). Время —
     * после ответа: пауза {@link #GAP} отсчитывается от него.
     */
    private void send(PendingNotification p) {
        try {
            client.sendMail(p.text(), p.silent());
        } finally {
            recent.addLast(clock.instant());
        }
    }

    /** Есть ли место в окне предела: попытки старше {@link #WINDOW} выбывают. */
    private boolean slotFree() {
        Instant from = clock.instant().minus(WINDOW);
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(from)) recent.pollFirst();
        return recent.size() < PER_WINDOW;
    }

    /** Пауза до {@link #GAP} от прошлой попытки; false — ожидание прервано. */
    private boolean waitGap() {
        if (recent.isEmpty()) return true;
        Duration wait = Duration.between(clock.instant(), recent.peekLast().plus(GAP));
        if (wait.isNegative() || wait.isZero()) return true;
        try {
            sleeper.sleep(wait);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 429 — пауза по retry_after (отсчёт от ответа, а не от начала пачки: отправки до него могли занять секунды),
     * сбоем подряд не считается. Прочее — нарастающая пауза {@link #backoff}.
     */
    private void pauseAfterFailure(Integer retryAfterSeconds) {
        Instant now = clock.instant();
        if (retryAfterSeconds != null) {
            pausedUntil = now.plusSeconds(retryAfterSeconds);
        } else {
            failuresInRow++;
            pausedUntil = now.plus(backoff(failuresInRow));
        }
    }
}
