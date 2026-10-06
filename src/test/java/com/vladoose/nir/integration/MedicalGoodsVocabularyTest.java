package com.vladoose.nir.integration;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MedicalGoodsVocabularyTest {
    @Test
    void multiWordTerm_matchesStemsInAnyOrderAndCase() {
        assertThat(MedicalGoodsVocabulary.matchesAny("Кислородный концентратор", List.of("концентратор кислород"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("Концентратор кислорода 5 л", List.of("концентратор кислород"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("Государственные закупки медицинских изделий", List.of("издели медицинск"))).isTrue();
    }
    @Test
    void stemMatchesOnlyAtWordStart() {
        assertThat(MedicalGoodsVocabulary.matchesAny("кузина", List.of("узи"))).isFalse();
        assertThat(MedicalGoodsVocabulary.matchesAny("Аппарат УЗИ", List.of("узи"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("тест-полоски", List.of("полоск"))).isTrue(); // дефис — граница слова
    }
    @Test
    void termWithShortStemIsMatchedAsPhrase() {   // «по» как начало слова есть почти в любом тексте
        assertThat(MedicalGoodsVocabulary.matchesAny("Работы по ремонту кровли", List.of("работы по"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("Поставка, выполнение работы и обучение", List.of("работы по"))).isFalse();
        assertThat(MedicalGoodsVocabulary.matchesAny("Объём работы поставщика", List.of("работы по"))).isFalse();
        assertThat(MedicalGoodsVocabulary.matchesAny("Работы\nпо ремонту", List.of("работы по"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("Работы  по ремонту", List.of("работы по"))).isTrue();
    }
    @Test
    void yoIsTreatedAsYe() {
        assertThat(MedicalGoodsVocabulary.matchesAny("Аппарат искусственной вентиляции лёгких", List.of("вентиляц легк"))).isTrue();
    }
    @Test
    void medicineMarkers() {
        assertThat(MedicalGoodsVocabulary.hasMedicine("Раствор для инфузий 0,9%")).isTrue();
        assertThat(MedicalGoodsVocabulary.hasMedicine("Таблетки 8 мг")).isTrue();
        assertThat(MedicalGoodsVocabulary.hasMedicine("Монитор пациента")).isFalse();
    }

    @Test
    void multiStemTerm_stemsMustBeWithinWindow() {   // ревью Task 3: стемы далеко друг от друга — не терм
        assertThat(MedicalGoodsVocabulary.matchesAny("Тележка медицинская", List.of("тележк медицин"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("Тележка один два три медицинская", List.of("тележк медицин"))).isTrue();   // 3 слова между — край окна
        assertThat(MedicalGoodsVocabulary.matchesAny("Медицинская для чего-то тележка", List.of("тележк медицин"))).isTrue();      // любой порядок
        assertThat(MedicalGoodsVocabulary.matchesAny("Тележка один два три четыре медицинская", List.of("тележк медицин"))).isFalse();
        assertThat(MedicalGoodsVocabulary.matchesAny("Тележка грузовая, колёса стальные, ручка из стали, для склада медицинского",
                List.of("тележк медицин"))).isFalse();
        // второе совпадение стема в окне спасает, даже если первое далеко
        assertThat(MedicalGoodsVocabulary.matchesAny("Кислород 5 л, один два три четыре, концентратор кислорода",
                List.of("концентратор кислород"))).isTrue();
    }

    @Test
    void underscoreTerm_isAdjacentPhraseInOrder() {
        assertThat(MedicalGoodsVocabulary.matchesAny("Тест полосы для глюкометра", List.of("тест_полос"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("Тест-полоски", List.of("тест_полос"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("Полоса стальная тестовая", List.of("тест_полос"))).isFalse();
        assertThat(MedicalGoodsVocabulary.matchesAny("на предметном стекле", List.of("предметн_стекл"))).isTrue();
    }

    @Test
    void nonMedicalGoodsDoNotHitStrongTerms() {   // ревью Task 3, пробы на goszakup name + descr
        for (String t : List.of("Тестер для определения подлинности банкнот",
                "Тестер аккумуляторов для определения уровня заряда",
                "Мука пшеничная для приготовления теста, определение влажности",
                "Полоса стальная тестовая", "Лента сигнальная красно-белая полоса, тестирование",
                "Контейнер вакуумный для хранения пищевых продуктов", "Стекло оконное предметная поверхность")) {
            assertThat(MedicalGoodsVocabulary.hasDeviceStrong(t)).as(t).isFalse();
        }
    }

    @Test
    void adjectiveStrongTermLosesToDrugForm_objectTermDoesNot() {
        assertThat(MedicalGoodsVocabulary.hasDeviceStrongOverDrugForm("Стоматологический анестетик раствор для инъекций")).isFalse();
        assertThat(MedicalGoodsVocabulary.hasDeviceStrongOverDrugForm("Гистологический фиксатор раствор формалина")).isFalse();
        assertThat(MedicalGoodsVocabulary.hasDeviceStrongOverDrugForm("Шприц для инъекций 5 мл")).isTrue();
        assertThat(MedicalGoodsVocabulary.hasDeviceStrongOverDrugForm("Набор стоматологических инструментов")).isTrue();
    }

    @Test
    void containerTermsNeedMedicalForm_notKrovatOrMochalka() {   // ревью Task 3, round 2: «кров» — кровать, «моч» — мочалка
        for (String t : List.of("Контейнер кровельный", "Контейнер для мочалок", "Контейнер пластиковый для ванной, мочалки",
                "Кровать с контейнером для белья", "Сборник мочалок")) {
            assertThat(MedicalGoodsVocabulary.hasDeviceStrong(t)).as(t).isFalse();
        }
        for (String t : List.of("Контейнер для крови", "Контейнер для сбора мочи", "Контейнер для биоматериала",
                "Контейнер для мочи стерильный", "Сборник мочи для детей")) {
            assertThat(MedicalGoodsVocabulary.hasDeviceStrong(t)).as(t).isTrue();
        }
    }

    @Test
    void adjectiveTermsAreStrongTerms() {   // вето формы снимает только то, что иначе засчиталось бы сильным
        assertThat(MedicalGoodsVocabulary.DEVICE_STRONG).containsAll(MedicalGoodsVocabulary.ADJECTIVE_STRONG);
    }
}
