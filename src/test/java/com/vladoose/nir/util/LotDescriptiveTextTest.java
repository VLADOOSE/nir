package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LotDescriptiveTextTest {

    /** Реальный goszakup-ТЗ: метки часто разорваны переносами PDF-извлечения (॰«Дополнительное\nописание лота:»). */
    private static final String GOSZAKUP_TZ = String.join("\n",
            "Приложение 2",
            "к конкурсной документации",
            "Номер закупки: № 17279420-1",
            "Наименование",
            "закупки:",
            "Резиновые пластинки для аппарата электрофореза \"Элэскулап\"",
            "Номер лота: № 86887302-ЗЦП3",
            "Наименование лота: Электрод",
            "Описание лота: платиновый",
            "Дополнительное",
            "описание лота:",
            "электроды силиконовые 55*80 мм",
            "Количество: 10",
            "Единица измерения: Комплект",
            "Места поставки: 231010000, Атырауская область, г.Атырау",
            "Срок поставки: в течение 2026 года (по заявке Заказчика)");

    @Test
    void goszakupTz_keepsDescriptive_dropsProcurementNoise() {
        String t = LotDescriptiveText.forMatching("Электрод", null, GOSZAKUP_TZ);
        // описательное — из «Дополнительное описание лота» (метка была разорвана переносом) сохранено
        assertThat(t.toLowerCase()).contains("силиконовые").contains("55").contains("80").contains("электрод");
        // закупочный канцелярит и адрес/номера — выброшены (иначе раздувают знаменатель score)
        assertThat(t).doesNotContain("17279420").doesNotContain("231010000");
        assertThat(t.toLowerCase()).doesNotContain("атырауская").doesNotContain("поставки");
    }

    @Test
    void requirementsForEmail_keepsTechDropsBoilerplate_noNameInjected() {
        String t = LotDescriptiveText.requirementsForEmail(GOSZAKUP_TZ);
        assertThat(t.toLowerCase()).contains("силиконовые").contains("55").contains("80"); // тех.суть
        assertThat(t).doesNotContain("17279420").doesNotContain("231010000");              // номер/адрес — вон
        assertThat(t.toLowerCase()).doesNotContain("поставки");
    }

    @Test
    void requirementsForEmail_blankOrNull_returnsEmpty() {
        assertThat(LotDescriptiveText.requirementsForEmail(null)).isEmpty();
        assertThat(LotDescriptiveText.requirementsForEmail("   ")).isEmpty();
    }

    @Test
    void specWithoutLabels_fallsBackToFullText() {
        // ручной/иной формат ТЗ без goszakup-меток → берём как есть (без регресса)
        String t = LotDescriptiveText.forMatching("Электрод", "Bar", "силиконовые электроды 55х80");
        assertThat(t).contains("Электрод").contains("Bar").contains("силиконовые электроды 55х80");
    }

    @Test
    void blankSpec_returnsNameAndManufactOnly() {
        assertThat(LotDescriptiveText.forMatching("Электрод", "Мед ТеКо", null)).isEqualTo("Электрод Мед ТеКо");
        assertThat(LotDescriptiveText.forMatching("Электрод", null, "  ")).isEqualTo("Электрод");
    }
}
