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
        // код лота (td[1]) = реальный № на площадке — ключ связи с файлами ТЗ (modal «Номер лота»)
        assertThat(first.code()).isEqualTo("1040409-Т1");
    }

    @Test
    void parse_empty_null_safe() {
        assertThat(SkPharmacyHtmlParser.parseSearch("")).isEmpty();
        assertThat(SkPharmacyHtmlParser.parseSearch(null)).isEmpty();
        assertThat(SkPharmacyHtmlParser.parseLots("<html><body>нет таблицы</body></html>")).isEmpty();
    }

    /** general.html — объявление 521304 (АО «КАЗМЕДТЕХ», лизингодатель): реальное ФИО секретаря + метка «Лизингодатель». */
    @Test
    void parseGeneral_lessor_binAddressKatoEmailContact() throws IOException {
        SkGeneral g = SkPharmacyHtmlParser.parseGeneral(fixture("general.html"));
        assertThat(g).isNotNull();
        assertThat(g.customerBin()).isEqualTo("101240007453");
        assertThat(g.legalAddress()).contains("Астана").contains("711510000");
        assertThat(g.regionKato()).isEqualTo("711510000");        // 9-значный КАТО из адреса, не 6-значный индекс
        assertThat(g.contactEmail()).isEqualTo("g.stepanenko@kmtlc.kz");
        assertThat(g.contactName()).isEqualTo("СТЕПАНЕНКО ГЕННАДИЙ");
    }

    /** general-distributor.html — 521464 (ТОО «СК-Фармация», единый дистрибьютор): метка организатора ДРУГАЯ. */
    @Test
    void parseGeneral_singleDistributor_labelVariantStillParsed() throws IOException {
        SkGeneral g = SkPharmacyHtmlParser.parseGeneral(fixture("general-distributor.html"));
        assertThat(g).isNotNull();
        assertThat(g.customerBin()).isEqualTo("090340007747");
        assertThat(g.regionKato()).isEqualTo("711210000");
        assertThat(g.contactEmail()).isEqualTo("t.omirbay@sk-pharmacy.kz");
        assertThat(g.legalAddress()).contains("Астана");
    }

    @Test
    void parseGeneral_null_safe() {
        assertThat(SkPharmacyHtmlParser.parseGeneral(null)).isNull();
        assertThat(SkPharmacyHtmlParser.parseGeneral("")).isNull();
        SkGeneral none = SkPharmacyHtmlParser.parseGeneral("<html><body>нет полей</body></html>");
        assertThat(none.customerBin()).isNull();
        assertThat(none.legalAddress()).isNull();
    }
}
