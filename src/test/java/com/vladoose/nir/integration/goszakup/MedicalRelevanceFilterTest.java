package com.vladoose.nir.integration.goszakup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MedicalRelevanceFilterTest {

    // --- один текст: медтовар или нет ---
    @Test
    void dropsNonMedicalAndServices() {
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Приобретения аппарат летательный беспилотный (дрон)")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Государственные закупки для ГУ Аппарат акима сельского округа")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Услуги по удалению медицинских опасных отходов класса Б")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("услуги планового медицинского осмотра работников")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("работы по пошиву медицинских халатов")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Офлайн обучение среднего медицинского персонала")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Текущий ремонт и монтаж натяжного потолка в актовом зале")).isFalse();
    }

    @Test
    void keepsMedicalGoods() {
        assertThat(MedicalRelevanceFilter.isMedicalGoods("УЗИ-сканер Mindray DC-70")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Аппарат искусственной вентиляции лёгких реанимационный")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Перчатки нитриловые смотровые стерильные")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Реагенты для гематологического анализатора")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Рентгеновский аппарат стационарный")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Изделия медицинского назначения одноразовые")).isTrue();
    }

    @Test
    void keepsRealZkoDevicesAndDropsMedicinesFoodHousehold() {
        // KEEP — реальная техника из лент больниц ЗКО (живой goszakup 2026-07-15)
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Облучатель")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Облучателя бактерицидного")).isTrue(); // родит. падеж
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Весы медицинские напольные")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Холодильник медицинский без морозильной камеры")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Концентратор кислорода")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Кровать функциональная механическая")).isTrue();
        // DROP — лекарства/еда/хозтовары/услуги (тоже реальные лоты этих больниц)
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Йодид калия (йодистый калий)")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Сульфадиазин")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Помидор")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Мыло туалетное")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Стартер для дизельного генератора")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Поверка средств измерений медицинского оборудования")).isFalse();
    }

    // --- тендер: ≥1 медтоварный лот → релевантен ---
    @Test
    void tenderRelevantIfAnyLotIsMedicalGoods() {
        // тендер «Перчатки + Покрывало» — остаётся ради перчаток
        assertThat(MedicalRelevanceFilter.isRelevant("Закуп изделий",
                List.of("Перчатки нитриловые стерильные", "Покрывало изотермическое спасательное"))).isTrue();
    }

    @Test
    void tenderDroppedIfAllLotsAreServicesOrNonMedical() {
        assertThat(MedicalRelevanceFilter.isRelevant("Разное",
                List.of("Услуги по удалению медицинских отходов", "обучение медперсонала"))).isFalse();
    }

    @Test
    void emptyLotsFallBackToAnnouncementName() {
        assertThat(MedicalRelevanceFilter.isRelevant("Аппарат ИВЛ экспертного класса", List.of())).isTrue();
        assertThat(MedicalRelevanceFilter.isRelevant("Аппарат акима села", List.of())).isFalse();
        assertThat(MedicalRelevanceFilter.isRelevant("Услуги медицинского осмотра", null)).isFalse();
    }
}
