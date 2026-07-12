package com.vladoose.nir.integration.skpharmacy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Парсер ТЗ-страниц на РЕАЛЬНЫХ фикстурах fms.ecc.kz (объявление 521464, docReqId 1608). */
class SkTechSpecHtmlParserTest {

    private String fixture(String name) throws IOException {
        try (var is = getClass().getResourceAsStream("/skpharmacy/" + name)) {
            assertThat(is).as("фикстура %s", name).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parseDocReqId_realFixture() throws IOException {
        assertThat(SkTechSpecHtmlParser.parseTechSpecDocReqId(fixture("documents.html"))).isEqualTo("1608");
    }

    @Test
    void parseModal_realFixture_perLotPdfs() throws IOException {
        List<SkTechSpecRef> refs = SkTechSpecHtmlParser.parseModal(fixture("techspec-modal.html"));
        assertThat(refs).hasSizeGreaterThanOrEqualTo(12);

        SkTechSpecRef first = refs.get(0);
        assertThat(first.lotCode()).isEqualTo("1040409-Т1");
        assertThat(first.pdfUrl()).startsWith("https://fms.ecc.kz").contains("/files/download_file/");
        assertThat(first.fileName()).isNotBlank();

        assertThat(refs).allSatisfy(r -> {
            assertThat(r.lotCode()).isNotBlank();
            assertThat(r.pdfUrl()).contains("download_file");
        });
        // код лота из модалки совпадает с тем, что мы храним у лота (SkLot.code) → join работает
        assertThat(refs).anySatisfy(r -> assertThat(r.lotCode()).isEqualTo("1040411-Т1"));
    }

    @Test
    void parse_nullSafe() {
        assertThat(SkTechSpecHtmlParser.parseTechSpecDocReqId("")).isNull();
        assertThat(SkTechSpecHtmlParser.parseTechSpecDocReqId("<html><body>нет строки ТЗ</body></html>")).isNull();
        assertThat(SkTechSpecHtmlParser.parseModal("")).isEmpty();
        assertThat(SkTechSpecHtmlParser.parseModal("<html><body>нет таблицы</body></html>")).isEmpty();
    }
}
