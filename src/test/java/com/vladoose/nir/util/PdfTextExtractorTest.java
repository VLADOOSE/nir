package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PdfTextExtractorTest {

    private byte[] fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/goszakup/techspec-pulse.pdf")) {
            return in.readAllBytes();
        }
    }

    @Test
    void extractsTextFromRealTechSpec() throws Exception {
        String text = PdfTextExtractor.extract(fixture());
        assertThat(text)
                .contains("Пульсоксиметр")
                .contains("Техническая спецификация")
                .contains("Диапазон измерения");
    }

    @Test
    void garbageBytesGiveNull() {
        assertThat(PdfTextExtractor.extract("не pdf".getBytes(StandardCharsets.UTF_8))).isNull();
    }

    @Test
    void nullAndEmptyGiveNull() {
        assertThat(PdfTextExtractor.extract(null)).isNull();
        assertThat(PdfTextExtractor.extract(new byte[0])).isNull();
    }
}
