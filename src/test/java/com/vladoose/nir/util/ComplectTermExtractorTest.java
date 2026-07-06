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
}
