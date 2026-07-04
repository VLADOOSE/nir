package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TechSpecExtractorTest {

    private static final String BILINGUAL = """
            Конкурстық құжаттамаға
            2-қосымша
            Конкурстық құжаттамаға сатып алынаты тауарлардың техникалық ерекшелігі
            Лоттың атауы : Пульсоксиметр
            Лоттың сипаттауы: медициналық
            Приложение 2
            к конкурсной документации
            Техническая спецификация закупаемых товаров к конкурсной документации
            Наименование лота: Пульсоксиметр
            Описание и требуемые функциональные, технические, качественные и
            эксплуатационные характеристики закупаемых товаров:
            Портативное устройство. Диапазон измерения сатурации: 0-100
            """;

    @Test
    void cutsRussianSectionFromBilingual() {
        String ru = TechSpecExtractor.russianSection(BILINGUAL);
        assertThat(ru)
                .startsWith("Приложение 2")
                .contains("Диапазон измерения сатурации: 0-100")
                .doesNotContain("Лоттың");
    }

    @Test
    void markerTechSpecWithoutPrilozhenie() {
        String text = "Кандай да бир мәтін\nТЕХНИЧЕСКАЯ СПЕЦИФИКАЦИЯ\nВес не более 45 кг";
        assertThat(TechSpecExtractor.russianSection(text))
                .startsWith("ТЕХНИЧЕСКАЯ СПЕЦИФИКАЦИЯ")
                .contains("45 кг");
    }

    @Test
    void noMarkersReturnsWholeTextTrimmed() {
        assertThat(TechSpecExtractor.russianSection("  просто текст ТЗ  "))
                .isEqualTo("просто текст ТЗ");
    }

    @Test
    void nullAndBlankGiveNull() {
        assertThat(TechSpecExtractor.russianSection(null)).isNull();
        assertThat(TechSpecExtractor.russianSection("   ")).isNull();
    }
}
