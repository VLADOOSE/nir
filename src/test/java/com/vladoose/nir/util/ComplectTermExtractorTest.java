package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComplectTermExtractorTest {

    @Test
    void extractsQuotedApparatusBrand() {
        String term = ComplectTermExtractor.extract("Электрод",
                "Резиновые пластинки для аппарата электрофореза \"Элэскулап\", размеры 55*80 мм");
        assertThat(term).isEqualTo("Элэскулап");
    }

    @Test
    void extractsBrandAfterApparatusKeyword_whenNotQuoted() {
        String term = ComplectTermExtractor.extract("Электрод",
                "электроды для аппарата Элэскулап 55х80");
        assertThat(term).isEqualTo("Элэскулап");
    }

    @Test
    void pureGenericText_returnsNull() {
        String term = ComplectTermExtractor.extract("Электрод",
                "электроды силиконовые электропроводящие, размеры 55*80 мм");
        assertThat(term).isNull();
    }

    @Test
    void skipsGoszakupLabelWords_extractsRealBrand() {
        // В goszakup-ТЗ строка «Наименование закупки: … ультразвукового аппарата Номер лота: …»
        // идёт РАНЬШЕ настоящего бренда «…аппарата Samsung Medison серий HS60» → регекс не должен
        // застревать на метке-разделе «Номер».
        String term = ComplectTermExtractor.extract("Датчик ультразвуковой",
                "Наименование закупки: Датчик для ультразвукового аппарата "
                        + "Номер лота: № 82509962-ОИ2 Дополнительное описание лота: "
                        + "Датчик СА2-9AD для ультразвукового аппарата Samsung Medison серий HS60.");
        assertThat(term).isEqualTo("Samsung");
    }
}
