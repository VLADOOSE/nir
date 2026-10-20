package com.vladoose.nir.service.tendernotify;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramClient;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Оповещение о сбое импорта площадки — в тему «Тендеры», рядом с уведомлениями о новых тендерах (тихо, как они).
 * Живой случай: 10.10.2026 goszakup 9 часов не отвечал с прода — все 10 автозапусков упали на всех 29 больницах,
 * а увидеть это можно было только в строке «Обновлено…» на экране.
 *
 * <p>Прогон «упал», если в нём есть ошибки и не записан ни один тендер (ни создан, ни обновлён): площадка недоступна
 * целиком, отвечает отказом или сменила вёрстку ленты. Частичный сбой (несколько больниц из 29) — не авария: он виден
 * в итоге прогона. Оповещение — один раз, когда подряд упали {@code failureRuns} прогонов (одиночный сбой — не авария),
 * и одно «снова работает» с первым удачным после него. Не дошло до Telegram — повтор со следующим прогоном.
 *
 * <p>Состояние — в памяти бина: рестарт посреди аварии начнёт счёт заново (повторное оповещение лучше потерянного).
 * Зовут оба планировщика импорта в конце каждого прогона — ручного и по расписанию; площадки считаются отдельно.
 */
@Component
public class ImportOutageNotifier {

    private static final Logger log = LoggerFactory.getLogger(ImportOutageNotifier.class);
    /** Сколько упавших прогонов подряд — авария: goszakup — 2 часа, СК-Фармация — от 4 часов (3 прогона в день). */
    static final int FAILURE_RUNS = 2;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    /** Отправка готового текста; в работе — тема тендеров Bot API. */
    interface Sender {
        void send(String text);
    }

    private static final class State {
        int failedRuns;
        LocalDateTime firstFailedAt;
        boolean alerted;
    }

    private final TelegramSettings telegram;
    private final Sender sender;
    private final boolean enabled;
    private final int failureRuns;
    private final Clock clock;
    private final Map<String, State> states = new HashMap<>();

    @Autowired
    public ImportOutageNotifier(TelegramSettings telegram, TelegramClient client,
                                @Value("${tenders.notify.enabled:false}") boolean enabled) {
        this(telegram, client::sendTenders, enabled, FAILURE_RUNS, Clock.systemUTC());
    }

    ImportOutageNotifier(TelegramSettings telegram, Sender sender, boolean enabled, int failureRuns, Clock clock) {
        this.telegram = telegram;
        this.sender = sender;
        this.enabled = enabled;
        this.failureRuns = failureRuns;
        this.clock = clock;
    }

    /**
     * Итог прогона площадки. Ничего не бросает: сбой оповещения не должен ронять итог прогона.
     * synchronized — goszakup и СК-Фармация заканчивают в разных потоках.
     *
     * @param platform «goszakup» / «СК-Фармации» (в родительном — «Импорт СК-Фармации не работает»): и ключ счёта, и подпись
     */
    public synchronized void afterRun(String platform, ImportSummary sum) {
        afterRun(platform, "Импорт " + platform, sum);
    }

    /**
     * То же для любого фонового прогона площадки (спека 2026-10-19-product-watch §7.5).
     *
     * @param key   ключ счёта аварии (площадка или «product-watch»)
     * @param title подпись: «Импорт goszakup» / «Поиск нашей продукции»
     */
    public synchronized void afterRun(String key, String title, ImportSummary sum) {
        String platform = key;
        if (!enabled || !telegram.isConfigured()) return;
        State st = states.computeIfAbsent(platform, p -> new State());
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone());
        if (failed(sum)) {
            if (st.failedRuns == 0) st.firstFailedAt = now;
            st.failedRuns++;
            if (!st.alerted && st.failedRuns >= failureRuns) {
                st.alerted = send(platform, down(title, st.failedRuns, st.firstFailedAt, sum));
            }
            return;
        }
        // «снова работает» — только если об аварии сообщали; не дошло — повтор со следующим удачным прогоном
        if (st.alerted && !send(platform, up(title, now, sum, st))) return;
        states.remove(platform);
    }

    /** Ошибки есть, а записано ничего: площадка недоступна целиком, а не отдельная больница или объявление. */
    static boolean failed(ImportSummary s) {
        return s.getErrors() > 0 && s.getCreated() + s.getUpdated() == 0;
    }

    private static String down(String title, int runs, LocalDateTime since, ImportSummary sum) {
        StringBuilder b = new StringBuilder("⚠️ ").append(title).append(" не работает: ").append(runs)
                .append(' ').append(runsWord(runs)).append(" подряд (с ").append(since.format(TIME))
                .append(") — ни один тендер не записан");
        String reason = TelegramText.oneLine(sum.getLastError(), 300);
        if (reason != null && !reason.isBlank()) b.append("\nПричина: ").append(reason);
        b.append("\nОшибок в последнем прогоне: ").append(sum.getErrors());
        b.append("\nНовые тендеры в АИС не попадают, пока площадка недоступна. Когда заработает — сообщу.");
        return b.toString();
    }

    private static String up(String title, LocalDateTime now, ImportSummary sum, State st) {
        return "✅ " + title + " снова работает: прогон " + now.format(TIME) + " прошёл (создано "
                + sum.getCreated() + ", обновлено " + sum.getUpdated() + ")\nНе работал с "
                + st.firstFailedAt.format(TIME) + ", упавших прогонов подряд: " + st.failedRuns;
    }

    /** 1 / 21 — «прогон», 2–4 / 22–24 — «прогона», 5–20 / 11–14 — «прогонов». */
    static String runsWord(int n) {
        int d = n % 10, h = n % 100;
        if (d == 1 && h != 11) return "прогон";
        if (d >= 2 && d <= 4 && (h < 12 || h > 14)) return "прогона";
        return "прогонов";
    }

    private boolean send(String platform, String text) {
        try {
            sender.send(text);
            log.info("Telegram: оповещение о сбое импорта {} отправлено", platform);
            return true;
        } catch (RuntimeException e) {
            log.warn("Telegram: оповещение о сбое импорта {} не ушло, повтор — со следующим прогоном: {}",
                    platform, ErrorText.of(e));
            return false;
        }
    }
}
