package com.vladoose.nir.util;

import com.vladoose.nir.util.PastedLinesParser.ParsedLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PastedLinesParserTest {

    private static ParsedLine one(String line) {
        List<ParsedLine> r = PastedLinesParser.parse(line);
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    @Test
    void quantityWithUnit() {
        assertThat(one("Тонометр OMRON M2 — 5 шт")).isEqualTo(new ParsedLine("Тонометр OMRON M2", 5, "шт"));
        assertThat(one("Рециркулятор РБм 3Х15 - 4 шт.")).isEqualTo(new ParsedLine("Рециркулятор РБм 3Х15", 4, "шт"));
        assertThat(one("Перчатки нитриловые 10 упак")).isEqualTo(new ParsedLine("Перчатки нитриловые", 10, "упак"));
        assertThat(one("Набор акушерский: 2 комплекта")).isEqualTo(new ParsedLine("Набор акушерский", 2, "компл"));
        assertThat(one("Шприц 5 мл — 100 шт")).isEqualTo(new ParsedLine("Шприц 5 мл", 100, "шт"));
    }

    @Test
    void quantityByTimesParenthesesAndDash() {
        assertThat(one("Кушетка медицинская x2")).isEqualTo(new ParsedLine("Кушетка медицинская", 2, null));
        assertThat(one("Термометр min/max ×10")).isEqualTo(new ParsedLine("Термометр min/max", 10, null));
        assertThat(one("Ширма 3-секционная х3")).isEqualTo(new ParsedLine("Ширма 3-секционная", 3, null));
        assertThat(one("Небулайзер (2)")).isEqualTo(new ParsedLine("Небулайзер", 2, null));
        assertThat(one("Небулайзер (2 шт)")).isEqualTo(new ParsedLine("Небулайзер", 2, "шт"));
        assertThat(one("Ростомер — 1")).isEqualTo(new ParsedLine("Ростомер", 1, null));
    }

    @Test
    void modelNumbersAreNotQuantities() {
        assertThat(one("Кровать функциональная КФ-2")).isEqualTo(new ParsedLine("Кровать функциональная КФ-2", 1, null));
        assertThat(one("Насадка №2")).isEqualTo(new ParsedLine("Насадка №2", 1, null));
        assertThat(one("Стол упаковочный 1600")).isEqualTo(new ParsedLine("Стол упаковочный 1600", 1, null));
        assertThat(one("Монитор SX2")).isEqualTo(new ParsedLine("Монитор SX2", 1, null));
        assertThat(one("Бинт 7м x 14см")).isEqualTo(new ParsedLine("Бинт 7м x 14см", 1, null));
        assertThat(one("Шприц 5 мл")).isEqualTo(new ParsedLine("Шприц 5 мл", 1, null));
    }

    @Test
    void sizesDecimalsAndProductUnitsAreNotQuantities() {
        // размер «N х M» в конце строки — не «x M штук»
        assertThat(one("Пелёнка впитывающая 60 х 90")).isEqualTo(new ParsedLine("Пелёнка впитывающая 60 х 90", 1, null));
        assertThat(one("Матрас 90 x 200")).isEqualTo(new ParsedLine("Матрас 90 x 200", 1, null));
        assertThat(one("Салфетка 45×45")).isEqualTo(new ParsedLine("Салфетка 45×45", 1, null));
        // десятичная запятая — часть характеристики, а не разделитель количества
        assertThat(one("Мешок Амбу 1,5 л")).isEqualTo(new ParsedLine("Мешок Амбу 1,5 л", 1, null));
        // кг, л, м описывают изделие, а не количество
        assertThat(one("Весы медицинские до 200 кг")).isEqualTo(new ParsedLine("Весы медицинские до 200 кг", 1, null));
        assertThat(one("Баллон кислородный 10 л")).isEqualTo(new ParsedLine("Баллон кислородный 10 л", 1, null));
        assertThat(one("Бинт марлевый 5 м")).isEqualTo(new ParsedLine("Бинт марлевый 5 м", 1, null));
        // а запятая с пробелом после — по-прежнему разделитель количества
        assertThat(one("Тонометр OMRON M2, 5 шт")).isEqualTo(new ParsedLine("Тонометр OMRON M2", 5, "шт"));
    }

    @Test
    void markersBlankLinesAndNumbersOnlyAreDropped() {
        List<ParsedLine> r = PastedLinesParser.parse("""
                1. Тонометр — 2 шт
                2) Кушетка x1

                • Ширма
                - Стойка инфузионная
                15
                """);
        assertThat(r).extracting(ParsedLine::name).containsExactly("Тонометр", "Кушетка", "Ширма", "Стойка инфузионная");
        assertThat(r).extracting(ParsedLine::quantity).containsExactly(2, 1, 1, 1);
    }

    @Test
    void nbspZeroAndHugeQuantity() {
        assertThat(one("Тонометр — 3 шт")).isEqualTo(new ParsedLine("Тонометр", 3, "шт"));
        assertThat(one("Тонометр — 0 шт").quantity()).isEqualTo(1);
        assertThat(one("Тонометр x999999").quantity()).isEqualTo(999_999);
    }

    @Test
    void capsAt200LinesAndTruncatesLongNames() {
        String text = "Позиция\n".repeat(250);
        assertThat(PastedLinesParser.parse(text)).hasSize(PastedLinesParser.MAX_LINES);
        assertThat(one("Я".repeat(400)).name()).hasSize(255);
        assertThat(PastedLinesParser.parse(null)).isEmpty();
        assertThat(PastedLinesParser.parse("   \n  ")).isEmpty();
    }
}
