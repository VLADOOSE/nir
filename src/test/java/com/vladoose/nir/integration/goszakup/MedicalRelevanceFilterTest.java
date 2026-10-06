package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.LotText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MedicalRelevanceFilterTest {

    private static boolean medical(String name) {
        return MedicalRelevanceFilter.isMedicalLot(new LotText(name, null));
    }

    private static List<LotText> lots(String... names) {
        return java.util.Arrays.stream(names).map(n -> new LotText(n, null)).toList();
    }

    // --- один текст: медтовар или нет ---
    @Test
    void dropsNonMedicalAndServices() {
        assertThat(medical("Приобретения аппарат летательный беспилотный (дрон)")).isFalse();
        assertThat(medical("Государственные закупки для ГУ Аппарат акима сельского округа")).isFalse();
        assertThat(medical("Услуги по удалению медицинских опасных отходов класса Б")).isFalse();
        assertThat(medical("услуги планового медицинского осмотра работников")).isFalse();
        assertThat(medical("работы по пошиву медицинских халатов")).isFalse();
        assertThat(medical("Офлайн обучение среднего медицинского персонала")).isFalse();
        assertThat(medical("Текущий ремонт и монтаж натяжного потолка в актовом зале")).isFalse();
    }

    @Test
    void keepsMedicalGoods() {
        assertThat(medical("УЗИ-сканер Mindray DC-70")).isTrue();
        assertThat(medical("Аппарат искусственной вентиляции лёгких реанимационный")).isTrue();
        assertThat(medical("Перчатки нитриловые смотровые стерильные")).isTrue();
        assertThat(medical("Реагенты для гематологического анализатора")).isTrue();
        assertThat(medical("Рентгеновский аппарат стационарный")).isTrue();
        assertThat(medical("Изделия медицинского назначения одноразовые")).isTrue();
    }

    @Test
    void keepsRealZkoDevicesAndDropsMedicinesFoodHousehold() {
        // KEEP — реальная техника из лент больниц ЗКО (живой goszakup 2026-07-15)
        assertThat(medical("Облучатель")).isTrue();
        assertThat(medical("Облучателя бактерицидного")).isTrue(); // родит. падеж
        assertThat(medical("Весы медицинские напольные")).isTrue();
        assertThat(medical("Холодильник медицинский без морозильной камеры")).isTrue();
        assertThat(medical("Концентратор кислорода")).isTrue();
        assertThat(medical("Кровать функциональная механическая")).isTrue();
        // DROP — лекарства/еда/хозтовары/услуги (тоже реальные лоты этих больниц)
        assertThat(medical("Йодид калия (йодистый калий)")).isFalse();
        assertThat(medical("Сульфадиазин")).isFalse();
        assertThat(medical("Помидор")).isFalse();
        assertThat(medical("Мыло туалетное")).isFalse();
        assertThat(medical("Стартер для дизельного генератора")).isFalse();
        assertThat(medical("Поверка средств измерений медицинского оборудования")).isFalse();
    }

    // --- тендер: ≥1 медтоварный лот → релевантен ---
    @Test
    void tenderRelevantIfAnyLotIsMedicalGoods() {
        // тендер «Перчатки + Покрывало» — остаётся ради перчаток
        assertThat(MedicalRelevanceFilter.isRelevant("Закуп изделий",
                lots("Перчатки нитриловые стерильные", "Покрывало изотермическое спасательное"))).isTrue();
    }

    @Test
    void tenderDroppedIfAllLotsAreServicesOrNonMedical() {
        assertThat(MedicalRelevanceFilter.isRelevant("Разное",
                lots("Услуги по удалению медицинских отходов", "обучение медперсонала"))).isFalse();
    }

    @Test
    void emptyLotsFallBackToAnnouncementName() {
        assertThat(MedicalRelevanceFilter.isRelevant("Аппарат ИВЛ экспертного класса", List.of())).isTrue();
        assertThat(MedicalRelevanceFilter.isRelevant("Аппарат акима села", List.of())).isFalse();
        assertThat(MedicalRelevanceFilter.isRelevant("Услуги медицинского осмотра", null)).isFalse();
    }

    @Test
    void deadTwoWordStemsNowMatch() {   // ревью A2: 7 стемов «стем пробел стем» не совпадали никогда
        for (String name : List.of("Кушетка медицинская смотровая", "Операционный стол", "Светильник операционный",
                "Кислородный концентратор", "Шовный материал", "Расходные материалы для гемодиализа",
                "Монитор прикроватный")) {
            assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText(name, null))).as(name).isTrue();
        }
    }

    @Test
    void serviceWordsInDescriptionDoNotKillDevice() {   // Review Focus 1
        assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Аппарат УЗИ",
                "Поставка, монтаж, пусконаладка и обучение персонала"))).isTrue();
    }

    @Test
    void weakServiceLosesToDeviceInName_strongServiceAlwaysWins() {
        assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Кушетка для осмотра", null))).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Ремонт аппарата УЗИ", null))).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Услуги по стерилизации", null))).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Медицинский осмотр работников", null))).isFalse();
    }

    @Test
    void deviceTermInDescriptionCounts() {
        assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Товар 1", "Анализатор гематологический"))).isTrue();
        // терм из двух стемов — стемы могут быть в разных полях («Тележка» / «медицинская»)
        assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Тележка", "медицинская"))).isTrue();
    }

    @Test
    void hvacVentilationIsNotMedical() {   // «вентиляц» ловил приточно-вытяжную вентиляцию
        assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Приточно-вытяжная вентиляция", null))).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Установка приточная",
                "для вентиляции, производительность 3 000 м3/час"))).isFalse();
    }

    @Test
    void weldingElectrodeIsNotMedical() {
        assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Электрод сварочный",
                "металлический, плавящийся, с покрытием"))).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Электроды ЭКГ одноразовые", null))).isTrue();
    }

    @Test
    void medicalWasteContainersAreGoods_wasteServicesAreNot() {   // решение 2026-10-07
        assertThat(medical("Контейнер для сбора и утилизации медицинских отходов")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Контейнер",
                "для сбора и утилизации медицинских отходов, пластиковый"))).isTrue();
        assertThat(medical("Коробка для сбора и хранения медицинских отходов")).isTrue();
        assertThat(medical("Пакет для медицинских отходов класса Б")).isTrue();
        assertThat(medical("КБУ 5 л")).isTrue();
        assertThat(medical("Вывоз медицинских отходов")).isFalse();
        assertThat(medical("Услуги по утилизации отходов в контейнерах")).isFalse();
        assertThat(medical("Услуги по утилизации медицинских отходов в контейнерах")).isFalse();
        assertThat(medical("Контейнер для сбора бытовых отходов")).isFalse();
    }
}
