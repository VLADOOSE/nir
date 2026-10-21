package com.vladoose.nir.tgbot;

import com.vladoose.nir.entity.AiVerdict;
import com.vladoose.nir.service.aianswer.AiAnswerParser;
import com.vladoose.nir.service.aianswer.AiAnswerParser.Line;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Разбор блока «Для АИС» (спека 2026-10-21-tender-telegram-bot §6) — любые формы, в которых ИИ его отдаёт. */
class AiAnswerParserTest {

    @Test
    void plainLines() {
        var p = AiAnswerParser.parse("L5826 | идти | P123 | ТЗ совпадает\nL5830 | не наше | — | нужен передвижной");
        assertThat(p.lines()).containsExactly(new Line(5826, AiVerdict.GO, 123L, "ТЗ совпадает"),
                new Line(5830, AiVerdict.NO, null, "нужен передвижной"));
        assertThat(p.unparsed()).isEmpty();
    }

    @Test
    void codeFence_markdownTable_brackets_listMarkers_cyrillicLetters() {
        String text = """
                Разбор: лот 1 подходит, лот 2 — нет.
                ```
                [L1] | Идти | [P7] | совпало 14 из 14
                ```
                | Лот | Вердикт | Товар | Почему |
                |---|---|---|---|
                | L2 | не ясно | — | нет данных о мощности |
                - Л3 | ИДТИ | Р9 | кириллица
                2. L4 | нет | - | не наш вид
                """;
        var p = AiAnswerParser.parse(text);
        assertThat(p.lines()).extracting(Line::lotId, Line::verdict, Line::productId).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, AiVerdict.GO, 7L),
                org.assertj.core.groups.Tuple.tuple(2L, AiVerdict.UNSURE, null),
                org.assertj.core.groups.Tuple.tuple(3L, AiVerdict.GO, 9L),
                org.assertj.core.groups.Tuple.tuple(4L, AiVerdict.NO, null));
        assertThat(p.unparsed()).isEmpty();   // заголовок и разделитель таблицы, рассуждение — не строки блока
    }

    @Test
    void productTextNotCode_goesToReason_duplicateLotTakesLast() {
        var p = AiAnswerParser.parse("L5 | идти | Nihon Kohden TEC-5600 | РУ РК-МТ-5№016668\nL6 | да | P1 | x\nL6 | не наше | — | передумал");
        assertThat(p.lines()).containsExactly(new Line(5, AiVerdict.GO, null, "Nihon Kohden TEC-5600; РУ РК-МТ-5№016668"),
                new Line(6, AiVerdict.NO, null, "передумал"));
    }

    @Test
    void badVerdict_isUnparsed_garbageIgnored_reasonCut() {
        String longWhy = "о".repeat(800);
        var p = AiAnswerParser.parse("L9 | может быть | P1 | ?\nпросто текст | с чертой\nL10 | идти | — | " + longWhy);
        assertThat(p.unparsed()).singleElement().asString().contains("L9");
        assertThat(p.lines()).singleElement().satisfies(l -> assertThat(l.reason()).hasSizeLessThanOrEqualTo(AiAnswerParser.REASON_MAX));
        assertThat(AiAnswerParser.parse(null).lines()).isEmpty();
        assertThat(AiAnswerParser.parse("без блока").lines()).isEmpty();
    }

    /** «Аналог» (решение оператора 2026-10-21): наш не подходит, подходит чужая модель с РУ в РК — «не наше» остаётся «не наше». */
    @Test
    void analogVerdict_words_notOursStaysNo() {
        var p = AiAnswerParser.parse("L1 | аналог | — | CUSA Clarity, Integra, РУ РК-МТ-5№019119\nL2 | другой | — | x\n"
                + "L3 | Чужой | — | y\nL4 | не наше, можно другой | — | z\nL5 | не наше | — | работы\nL6 | **Аналог** | — | w");
        assertThat(p.lines()).extracting(Line::lotId, Line::verdict).containsExactly(
                tuple(1L, AiVerdict.ANALOG), tuple(2L, AiVerdict.ANALOG), tuple(3L, AiVerdict.ANALOG),
                tuple(4L, AiVerdict.ANALOG), tuple(5L, AiVerdict.NO), tuple(6L, AiVerdict.ANALOG));
        assertThat(p.lines().get(0).reason()).isEqualTo("CUSA Clarity, Integra, РУ РК-МТ-5№019119");
        assertThat(p.unparsed()).isEmpty();
    }

}
