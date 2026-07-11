package com.vladoose.nir.integration.skpharmacy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkPharmacyRelevanceFilterTest {

    @Test
    void deviceLots_in() {
        assertThat(SkPharmacyRelevanceFilter.isDeviceLot("компьютерный томограф")).isTrue();
        assertThat(SkPharmacyRelevanceFilter.isDeviceLot("Аппарат ИВЛ наркозно-дыхательный")).isTrue();
        assertThat(SkPharmacyRelevanceFilter.isDeviceLot("Магнитно-резонансный томограф (безгелиевый)")).isTrue();
        assertThat(SkPharmacyRelevanceFilter.isDeviceLot("Перчатки смотровые нитриловые")).isTrue();
    }

    @Test
    void medicineLots_out() {
        assertThat(SkPharmacyRelevanceFilter.isDeviceLot("Парацетамол таблетки 500 мг")).isFalse();
        assertThat(SkPharmacyRelevanceFilter.isDeviceLot("Натрия хлорид раствор для инфузий 0,9%")).isFalse();
        assertThat(SkPharmacyRelevanceFilter.isDeviceLot("Инсулин человеческий")).isFalse();
        assertThat(SkPharmacyRelevanceFilter.isDeviceLot("Амоксициллин капсулы 250 мг")).isFalse();
    }

    @Test
    void tenderRelevant_ifAnyDeviceLot() {
        assertThat(SkPharmacyRelevanceFilter.isRelevant("Закуп", List.of(
                "Парацетамол таблетки 500 мг", "компьютерный томограф"))).isTrue();   // ≥1 device
        assertThat(SkPharmacyRelevanceFilter.isRelevant("Закуп", List.of(
                "Парацетамол таблетки 500 мг", "Инсулин человеческий"))).isFalse();   // все лекарства
    }

    @Test
    void nameStage_dropsObviousMedicines() {
        assertThat(SkPharmacyRelevanceFilter.nameCandidate("Закуп лекарственных средств")).isFalse();
        assertThat(SkPharmacyRelevanceFilter.nameCandidate("Закуп медицинской техники")).isTrue();
        assertThat(SkPharmacyRelevanceFilter.nameCandidate("Закуп медицинских изделий")).isTrue();
        // «препарат» но с изделиями → не отсекаем на ступени 1 (решит ступень 2 по лотам)
        assertThat(SkPharmacyRelevanceFilter.nameCandidate("Препараты и медицинские изделия")).isTrue();
    }

    @Test
    void emptyLots_fallbackToName() {
        assertThat(SkPharmacyRelevanceFilter.isRelevant("Закуп медицинской техники", List.of())).isTrue();
        assertThat(SkPharmacyRelevanceFilter.isRelevant("Закуп лекарственных средств", List.of())).isFalse();
    }
}
