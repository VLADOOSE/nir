package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.productwatch.ProductFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** «ТЗ под чужой бренд» (спека §5.5) — на настоящих ТЗ набора и литералах. */
class ForeignBrandTest {

    static final Set<String> OURS = ForeignBrand.ours(List.of("Генератор ONYX", "Биполярные пинцеты, серия \"CLEANTips\"",
            "Кабель нейтрального электрода с разъемом типа Valleylab. Длина кабеля 5 м"), List.of("Фотек", "Аксион", "Завод ЭМА"));

    static String in(String file) {
        return ForeignBrand.detect(SpecRequirements.body(ProductFixtures.spec(file)), OURS, Set.of());
    }

    @Test
    void bowaByModelCode() {
        assertThat(in("17828560-1_88272401-ЗЦП1.pdf")).isEqualTo("BOWA");
    }

    @Test
    void zollRepeated() {
        assertThat(in("17723120-1_87797199-ОК2.pdf")).isEqualTo("ZOLL");
    }

    /** «Датчик SpO2 NELLCOR», «на пульсоксиметр Mindray» — совместимость, не ТЗ под бренд. */
    @Test
    void compatibilityIsNotForeign() {
        assertThat(in("10461874-1_26702147-ОЛ-ОК2.pdf")).isNull();
        assertThat(in("10066644-1_26037428-ОЛ-ОИ2.pdf")).isNull();
    }

    /** ТЗ Астаны списано с АСД-«ФОТЕК» — чужого бренда нет. */
    @Test
    void copiedFromOurs_noForeign() {
        assertThat(in("17779323-1.pdf")).isNull();
    }

    /** Ручка-чехол для генераторов Covidien (режим Valleylab): ТЗ под чужое оборудование. */
    @Test
    void covidienAccessory() {
        assertThat(in("17714835-1_87662104-ОИ2.pdf")).isIn("Covidien", "Valleylab");
    }

    /** Review Focus #4: наш бренд и чужой в одном ТЗ — чужой «BOWA», наш — нет. */
    @Test
    void oursAndForeignTogether() {
        assertThat(ForeignBrand.detect("Аппарат BOWA ARC 303 или эквивалент, например ФОТЕК ЭХВЧ-350; генератор ONYX", OURS, Set.of()))
                .isEqualTo("BOWA");
        assertThat(ForeignBrand.detect("Генератор ONYX, серия CLEANTips; аппарат ONYX-A", OURS, Set.of())).isNull();
    }

    @Test
    void fieldsAndCountry() {
        assertThat(ForeignBrand.detect("Производитель: Mindray или эквивалент", OURS, Set.of())).isEqualTo("Mindray");
        assertThat(ForeignBrand.detect("Страна производитель: Германия", OURS, Set.of())).isEqualTo("Германия");
        assertThat(ForeignBrand.detect("Страна производитель: Россия", OURS, Set.of())).isNull();
        assertThat(ForeignBrand.detect("Производитель: указать", OURS, Set.of())).isNull();
    }

    /** Бренд из реестра — и одиночный, без кода модели; служебные слова латиницей — нет. */
    @Test
    void knownRegistryBrand_andStopWords() {
        Set<String> known = ForeignBrand.registryBrands(List.of("Shenzhen Mindray Bio-Medical Electronics Co., Ltd", "Philips Medical Systems"));
        assertThat(known).contains("mindray", "philips").doesNotContain("shenzhen", "medical", "electronics", "systems");
        assertThat(ForeignBrand.detect("монитор аналог Philips", OURS, known)).isEqualTo("Philips");
        assertThat(ForeignBrand.detect("Дисплей TFT, интерфейс USB, датчик SpO2, поставка DDP", OURS, known)).isNull();
    }

    /** Дефибриллятор 17719509-1 («наш вид своими словами»): «протокол … (AAMI DF-80)», «IEEE 802.11» — стандарты, не бренды. */
    @Test
    void standardsAreNotBrands() {
        assertThat(in("17719509-1_83002565-ЗЦП1.pdf")).isNull();
        assertThat(ForeignBrand.detect("Соответствует требованиям ANSI/AAMI EC13; беспроводная связь IEEE 802.11; ASTM F1980", OURS, Set.of()))
                .isNull();
        assertThat(ForeignBrand.detect("Отведения I, II, III, aVR; класс защиты III; категория XII", OURS, Set.of())).isNull();
    }
    /**
     * Проверка на проде после выкладки (2026-10-20): термины латиницей давали «ТЗ под …». CMRR — число после «:»
     * принималось за код модели, APNEA — номер следующего пункта, CRI и light touch — сокращение в скобках после
     * русского термина, повторённое вместе с абзацем. Настоящие производители тех же ТЗ — по-прежнему чужой бренд.
     */
    @Test
    void termsAreNotBrands_liveSpecs() {
        assertThat(ForeignBrand.detect("Коэффициент ослабления синфазного сигнала CMRR: ≥ 135 дБ (если режекторный фильтр включен; "
                + "≥ 115 дБ (если выключен)", OURS, Set.of())).isNull();
        assertThat(ForeignBrand.detect("– Модуль SpO2 отсоединён; – Модуль CO2 отсоединён; – APNEA (апноэ); 5. Настройки оператора",
                OURS, Set.of())).isNull();
        assertThat(ForeignBrand.detect("Регулировка освещенности: от 10% до 100%. Индекс цветопередачи (CRI): 97 Ra. Описание: "
                + "Регулировка освещенности: от 10% до 100%. Индекс цветопередачи (CRI): 97 Ra", OURS, Set.of())).isNull();
        assertThat(ForeignBrand.detect("Должно быть обеспечено цифровое сенсорное управление (light touch) на корпусе блока. "
                + "Система управления: не менее 5 шагов регулировки яркости Light Touch и включение/выключение", OURS, Set.of())).isNull();
        assertThat(ForeignBrand.detect("Воздушный фильтр HEPA класса 13, предфильтр", OURS, Set.of())).isNull();   // через русское слово — не код
        // обычные слова среди производителей реестра («Light Instruments Ltd.») и условия поставки — не бренд
        Set<String> registry = ForeignBrand.registryBrands(List.of("Light Instruments Ltd.", "BG LIGHT LTD", "Cardio Medical Center",
                "Integra LifeSciences (Ireland) Limited"));
        assertThat(ForeignBrand.detect("регулировки яркости Light Touch и включение; программа Therapy Center", OURS, registry)).isNull();
        assertThat(ForeignBrand.detect("Условия поставки – DDP (Incoterms 2020) до места эксплуатации", OURS, Set.of())).isNull();
        assertThat(ForeignBrand.detect("Аппарат искусственной вентиляции легких HAMILTON-C6 Производитель Hamilton Medical AG, Швейцария",
                OURS, Set.of())).isEqualTo("HAMILTON");
        assertThat(ForeignBrand.detect("Кислородный баллон на транспортный МВЛ аппарат Drager Oxylog 3000 plus", OURS, Set.of()))
                .isEqualTo("Drager");
    }
}
