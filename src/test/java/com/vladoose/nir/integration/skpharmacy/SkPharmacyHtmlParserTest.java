package com.vladoose.nir.integration.skpharmacy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Парсер на РЕАЛЬНЫХ фикстурах, снятых с fms.ecc.kz (search.html — searchanno; lots.html — объявление 521464). */
class SkPharmacyHtmlParserTest {

    private String fixture(String name) throws IOException {
        try (var is = getClass().getResourceAsStream("/skpharmacy/" + name)) {
            assertThat(is).as("фикстура %s", name).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parseSearch_realFixture_10announces_firstFields() throws IOException {
        List<SkAnnounce> list = SkPharmacyHtmlParser.parseSearch(fixture("search.html"));
        assertThat(list).hasSize(10);
        SkAnnounce a = list.get(0);
        assertThat(a.announceId()).isEqualTo("521464");
        assertThat(a.numberAnno()).isEqualTo("521464-1");
        assertThat(a.nameRu().toLowerCase()).contains("медицинск");
        assertThat(a.purchaseType()).isEqualTo("Тендер");
        assertThat(a.lotsCount()).isEqualTo(12);
        assertThat(a.totalSum()).isEqualByComparingTo("15085999992.00");
        assertThat(a.status()).isEqualTo("Опубликовано");
    }

    @Test
    void parseLots_realFixture_deviceLots() throws IOException {
        List<SkLot> lots = SkPharmacyHtmlParser.parseLots(fixture("lots.html"));
        assertThat(lots).isNotEmpty();
        assertThat(lots).anySatisfy(l -> assertThat(l.name().toLowerCase()).contains("томограф"));
        SkLot first = lots.get(0);
        assertThat(first.name()).isNotBlank();
        assertThat(first.quantity()).isNotNull();
        assertThat(first.unitPrice()).isNotNull();
    }

    @Test
    void parse_empty_null_safe() {
        assertThat(SkPharmacyHtmlParser.parseSearch("")).isEmpty();
        assertThat(SkPharmacyHtmlParser.parseSearch(null)).isEmpty();
        assertThat(SkPharmacyHtmlParser.parseLots("<html><body>нет таблицы</body></html>")).isEmpty();
    }
}
