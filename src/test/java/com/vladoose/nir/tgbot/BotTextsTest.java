package com.vladoose.nir.tgbot;

import com.vladoose.nir.entity.AiVerdict;
import com.vladoose.nir.entity.BotLetter;
import com.vladoose.nir.entity.BotLetterStatus;
import com.vladoose.nir.integration.telegram.TelegramBotApi.Button;
import com.vladoose.nir.service.aianswer.AiAnswerResult;
import com.vladoose.nir.service.aianswer.AiAnswerResult.LetterPlan;
import com.vladoose.nir.service.aianswer.AiAnswerResult.LotResult;
import com.vladoose.nir.service.aianswer.AiAnswerResult.Outcome;
import com.vladoose.nir.service.tgbot.BotTexts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Тексты бота (спека 2026-10-21-tender-telegram-bot §6–8). */
class BotTextsTest {

    static BotLetter letter(long id, int n, BotLetterStatus st) {
        return BotLetter.builder().id(id).tenderId(1L).distributorId(2L).lineNo(n).status(st).build();
    }

    @Test
    void unknownUserNamesIdAndWhereToPutIt() {
        assertThat(BotTexts.unknownUser(111)).contains("111").contains("«Пользователи»");
    }

    @Test
    void tendersPlural() {
        assertThat(List.of(1, 3, 5, 11, 21).stream().map(BotTexts::tenders))
                .containsExactly("1 тендер", "3 тендера", "5 тендеров", "11 тендеров", "21 тендер");
    }

    @Test
    void summaryEscapesAndListsLetters() {
        AiAnswerResult r = new AiAnswerResult(
                List.of(new LotResult(5, 1L, "177-1", "Лот <1>", "ГБ", AiVerdict.GO, Outcome.PINNED, "ДКИ",
                        "✅ «Лот <1>» · ГБ → ДКИ — закреплено")),
                List.of("L9 | может быть"),
                List.of(new LetterPlan(1, "177-1", "ГБ", 2, "ZZ Завод", true, List.of(5L), List.of("Дефибриллятор"))),
                List.of("«Облучатель» — у ZZ уже запрошено 20.10"));
        assertThat(BotTexts.summary(r)).contains("лотов: 1").contains("«Лот &lt;1&gt;»").contains("Не понял строк: 1")
                .contains("• «Облучатель» — у ZZ уже запрошено 20.10")
                .contains("1. ZZ Завод (завод) — Дефибриллятор · ГБ");
        assertThat(BotTexts.summary(new AiAnswerResult(List.of(), List.of(), List.of(), List.of())))
                .contains("Писем к отправке нет");
    }

    @Test
    void listKeyboardByStatus_fourPerRow() {
        List<List<Button>> kb = BotTexts.listKeyboard(List.of(letter(10, 1, BotLetterStatus.DRAFT),
                letter(11, 2, BotLetterStatus.SENT), letter(12, 3, BotLetterStatus.ELSEWHERE),
                letter(13, 4, BotLetterStatus.CANCELLED), letter(14, 5, BotLetterStatus.FAILED)));
        assertThat(kb).hasSize(2);
        assertThat(kb.get(0)).extracting(Button::text, Button::data).containsExactly(tuple("👁 1", "l:10:v"),
                tuple("✅ 2", "l:11:x"), tuple("✅ 3 · АИС", "l:12:x"), tuple("✕ 4", "l:13:x"));
        assertThat(kb.get(1)).extracting(Button::data).containsExactly("l:14:v");   // не ушло — посмотреть и отправить ещё раз
    }

    /** Ревью ветки: ответ ИИ по пачке из 15 тендеров с причинами — список писем (подписи кнопок) не должен пропасть. */
    @Test
    void longAnswer_lettersAlwaysListed_lotsCutWithCount() {
        String why = "длинная причина ".repeat(30);
        List<LotResult> lots = LongStream.rangeClosed(1, 40).mapToObj(i -> new LotResult(i, 1L, "177-1", "Лот " + i, "ГБ",
                AiVerdict.NO, Outcome.NOT_OURS, null, "✕ «Лот " + i + "» · ГБ — не наше: " + why)).toList();
        List<LetterPlan> letters = List.of(
                new LetterPlan(1, "177-1", "ГБ", 2, "ZZ Завод", true, List.of(5L), List.of("Дефибриллятор")),
                new LetterPlan(1, "177-1", "ГБ", 3, "ZZ Доверенный", false, List.of(6L), List.of("Облучатель")));
        String s = BotTexts.summary(new AiAnswerResult(lots, List.of(), letters, List.of("«Облучатель» — у ZZ уже запрошено 20.10")));
        assertThat(s).contains("«Лот 1»").containsPattern("…и ещё \\d+ лот(а|ов)? — вывод ИИ у лотов в АИС")
                .contains("1. ZZ Завод (завод) — Дефибриллятор · ГБ")
                .contains("2. ZZ Доверенный (доверенный) — Облучатель · ГБ");
        assertThat(s.length()).isLessThan(4096);
    }

    @Test
    void manyLetters_cutWithCount_underTelegramLimit() {
        List<LetterPlan> letters = IntStream.rangeClosed(1, 40).mapToObj(i -> new LetterPlan(i, "177-" + i, "ГБ", i,
                "ZZ Поставщик " + i, true, List.of((long) i), List.of("Очень длинное наименование лота ".repeat(8)))).toList();
        String s = BotTexts.summary(new AiAnswerResult(List.of(), List.of(), letters, List.of()));
        assertThat(s).contains("1. ZZ Поставщик 1 (завод)").containsPattern("…и ещё \\d+ — номера на кнопках ниже");
        assertThat(s.length()).isLessThan(4096);
    }


    /** Текст для получателя в WhatsApp (кнопка copy_text — до 256 знаков): номер запроса, позиции, больница, срок. */
    @Test
    void whatsappText_fitsCopyButton() {
        String t = BotTexts.whatsappText(145, List.of("Дефибриллятор автоматический наружный ДА-Н-01 «Аксион» — 1 шт."),
                "Областная офтальмологическая больница", java.time.LocalDate.of(2026, 10, 22));
        assertThat(t).contains("№ 145").contains("ДА-Н-01").contains("Конечный заказчик: Областная офтальмологическая больница")
                .contains("22.10.2026").contains("West-Med").doesNotContain("..");   // «— 1 шт.» + «. Конечный…» — одна точка
        String longOne = BotTexts.whatsappText(146, List.of("Очень длинная позиция ".repeat(20), "Ещё позиция ".repeat(10)),
                "ГБ", java.time.LocalDate.of(2026, 10, 22));
        assertThat(longOne).hasSizeLessThanOrEqualTo(256).contains("№ 146").contains("Конечный заказчик: ГБ").contains("22.10.2026");
    }

    @Test
    void listKeyboard_manualStates() {
        List<List<Button>> kb = BotTexts.listKeyboard(List.of(letter(20, 1, BotLetterStatus.MANUAL), letter(21, 2, BotLetterStatus.HANDED)));
        assertThat(kb.get(0)).extracting(Button::text, Button::data).containsExactly(tuple("⏳ 1 · сам", "l:20:x"), tuple("✅ 2 · сам", "l:21:x"));
    }

}
