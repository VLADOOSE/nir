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

    @Test
    void strongDevicesRecognizedDespiteMedicineWords() {   // ревью A1
        for (String n : List.of("Анализатор биохимический автоматический", "Гастроскоп", "Бронхоскоп",
                "Микроскоп бинокулярный", "Центрифуга лабораторная", "Маммограф цифровой", "Насос инфузионный",
                "Капсула эндоскопическая", "Тест-полоски для глюкометра", "Экспресс-тест для определения тропонина",
                "Реагент для определения глюкозы в сыворотке крови", "Шприц инъекционный 5 мл")) {
            assertThat(SkPharmacyRelevanceFilter.isDeviceLot(n)).as(n).isTrue();
        }
    }

    @Test
    void medicinesStayOut() {
        for (String n : List.of("Раствор для инфузий натрия хлорида 0,9%", "Таблетки 8 мг", "Вакцина против гриппа",
                "Аэрозол для ингаляций", "Капсулы 20 мг")) {
            assertThat(SkPharmacyRelevanceFilter.isDeviceLot(n)).as(n).isFalse();
        }
    }

    @Test
    void weakDeviceWordLosesToMedicine() {
        assertThat(SkPharmacyRelevanceFilter.isDeviceLot("Набор для инфузий")).isFalse();      // набор — слабое, инфузи — вето
        assertThat(SkPharmacyRelevanceFilter.isDeviceLot("Набор хирургических инструментов")).isTrue(); // хирургическ — сильное
    }

    @Test
    void announcementNamedMedicalEquipmentIsRelevantWhateverLots() {
        assertThat(SkPharmacyRelevanceFilter.isRelevant("Закуп медицинской техники", List.of("Ларингоскоп", "Прочее"))).isTrue();
        assertThat(SkPharmacyRelevanceFilter.isRelevant("Закуп медицинских изделий на 2026 год", List.of("Х"))).isTrue();
    }
}
