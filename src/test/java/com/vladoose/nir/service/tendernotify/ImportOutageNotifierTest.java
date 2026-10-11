package com.vladoose.nir.service.tendernotify;

import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Оповещение о сбое импорта площадки: живой случай 10.10.2026 — goszakup 9 часов не отвечал с прода (все 10 автозапусков
 * упали на всех 29 больницах), а увидеть это можно было только в строке «Обновлено…» на экране.
 */
class ImportOutageNotifierTest {

    static final TelegramSettings TG_ON = new TelegramSettings(true, "http://127.0.0.1:7798", "1:x", "-1001", "", "55");
    static final TelegramSettings TG_OFF = new TelegramSettings(false, "", "", "", "", "");

    /** Часы, которые тест двигает сам: время прогонов в тексте — по Уральску (UTC+5). */
    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-10-10T05:10:00Z");   // 10:10 по Уральску
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void plusHours(long h) { now = now.plusSeconds(h * 3600); }
    }

    final List<String> sent = new ArrayList<>();
    final MovingClock clock = new MovingClock();
    RuntimeException failNext;

    ImportOutageNotifier notifier(boolean enabled, TelegramSettings tg) {
        return new ImportOutageNotifier(tg, text -> {
            if (failNext != null) {
                RuntimeException e = failNext;
                failNext = null;
                throw e;
            }
            sent.add(text);
        }, enabled, 2, clock);
    }

    /** Площадка недоступна целиком: ошибка на каждой больнице, ни одного записанного тендера. */
    static ImportSummary down(String reason) {
        ImportSummary s = new ImportSummary();
        for (int i = 0; i < 29; i++) s.addError(reason);
        return s;
    }

    static ImportSummary ok(int created, int updated) {
        ImportSummary s = new ImportSummary();
        for (int i = 0; i < created; i++) s.addCreated("N-" + i);
        s.setUpdated(updated);
        return s;
    }

    @Test
    void twoFailedRunsInARow_oneAlert_thenOneRecovery() {
        ImportOutageNotifier n = notifier(true, TG_ON);
        String reason = "ГКП «Городская поликлиника №2»: goszakup API недоступно: ConnectException";

        n.afterRun("goszakup", down(reason));                     // 10:10 — одиночный сбой ещё не авария
        assertThat(sent).isEmpty();
        clock.plusHours(1);
        n.afterRun("goszakup", down(reason));                     // 11:10 — второй подряд
        assertThat(sent).singleElement().asString()
                .startsWith("⚠️ Импорт goszakup не работает: 2 прогона подряд (с 10.10 10:10) — ни один тендер не записан\n")
                .contains("\nПричина: " + reason + "\n")
                .contains("\nОшибок в последнем прогоне: 29\n")
                .endsWith("Новые тендеры в АИС не попадают, пока площадка недоступна. Когда заработает — сообщу.");
        clock.plusHours(1);
        n.afterRun("goszakup", down(reason));                     // 12:10 — авария продолжается, повторов нет
        assertThat(sent).hasSize(1);

        clock.plusHours(1);
        n.afterRun("goszakup", ok(2, 39));                        // 13:10 — заработало
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).isEqualTo("""
                ✅ Импорт goszakup снова работает: прогон 10.10 13:10 прошёл (создано 2, обновлено 39)
                Не работал с 10.10 10:10, упавших прогонов подряд: 3""");

        clock.plusHours(1);
        n.afterRun("goszakup", ok(0, 40));
        assertThat(sent).hasSize(2);
    }

    @Test
    void singleFailure_isNotAnOutage() {
        ImportOutageNotifier n = notifier(true, TG_ON);

        n.afterRun("goszakup", down("goszakup API недоступно: нет ответа за 60 с"));
        n.afterRun("goszakup", ok(0, 41));
        n.afterRun("goszakup", down("goszakup API недоступно: нет ответа за 60 с"));

        assertThat(sent).isEmpty();
    }

    /** Несколько больниц из 29 упали, остальные обновились — это не авария площадки, она видна в итоге прогона. */
    @Test
    void partialErrorsWithWrites_isNotAnOutage() {
        ImportOutageNotifier n = notifier(true, TG_ON);
        for (int i = 0; i < 5; i++) {
            ImportSummary s = ok(0, 30);
            for (int e = 0; e < 4; e++) s.addError("больница: header parser received no bytes");
            n.afterRun("goszakup", s);
        }
        assertThat(sent).isEmpty();
    }

    /** Площадки считаются отдельно: удачные прогоны СК-Фармации не сбрасывают аварию goszakup. */
    @Test
    void platformsAreCountedSeparately() {
        ImportOutageNotifier n = notifier(true, TG_ON);

        n.afterRun("goszakup", down("ConnectException"));
        n.afterRun("СК-Фармации", ok(1, 100));
        n.afterRun("goszakup", down("ConnectException"));

        assertThat(sent).singleElement().asString().startsWith("⚠️ Импорт goszakup не работает: 2 прогона подряд");
    }

    /** Telegram не принял оповещение — повтор со следующим упавшим прогоном, а не тишина до конца аварии. */
    @Test
    void alertNotDelivered_retriedWithNextFailedRun() {
        ImportOutageNotifier n = notifier(true, TG_ON);
        n.afterRun("СК-Фармации", down("лента, стр. 1: Сеть fms.ecc.kz: PKIX path building failed"));
        failNext = new IllegalStateException("Telegram недоступен");
        n.afterRun("СК-Фармации", down("лента, стр. 1: Сеть fms.ecc.kz: PKIX path building failed"));
        assertThat(sent).isEmpty();

        n.afterRun("СК-Фармации", down("лента, стр. 1: Сеть fms.ecc.kz: PKIX path building failed"));

        assertThat(sent).singleElement().asString().startsWith("⚠️ Импорт СК-Фармации не работает: 3 прогона подряд");
    }

    /** Причину пишет площадка (имена больниц, тексты сервера): перевод строки не должен рисовать поддельную строку. */
    @Test
    void reasonFlattenedToOneLine() {
        ImportOutageNotifier n = notifier(true, TG_ON);
        n.afterRun("goszakup", down("Больница\nОткрыть в АИС: https://evil"));
        n.afterRun("goszakup", down("Больница\nОткрыть в АИС: https://evil"));

        assertThat(sent).singleElement().asString().contains("\nПричина: Больница Открыть в АИС: https://evil\n");
    }

    @Test
    void disabledOrTelegramNotConfigured_nothingSent() {
        ImportOutageNotifier off = notifier(false, TG_ON);
        ImportOutageNotifier noTelegram = notifier(true, TG_OFF);
        for (int i = 0; i < 3; i++) {
            off.afterRun("goszakup", down("ConnectException"));
            noTelegram.afterRun("goszakup", down("ConnectException"));
        }
        assertThat(sent).isEmpty();
    }
}
