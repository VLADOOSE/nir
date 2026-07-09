package com.vladoose.nir.integration.goszakup;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class GoszakupImportRelevanceTest {

    // Ступень-1 список (дефолт yaml) — держим копию инварианта: реальные девайсы покрыты специфичным словом.
    private static final List<String> STAGE1 = List.of(
            "узи","ультразвук","томограф","рентген","флюорограф","маммограф","монитор пациента","дефибриллятор",
            "ивл","вентиляц","наркоз","анализатор","центрифуг","стерилизатор","автоклав","эндоскоп","гастроскоп",
            "колоноскоп","электрокардиограф","кардиомонитор","инкубатор","облучатель","спирометр","хирургическ",
            "реанимац","эхокардиограф","ангиограф","коагулометр","микроскоп","дефибрил","перчат","шприц","катетер",
            "реагент","тест-систем","изделие медицинск","изделия медицинск","медицинского назначения",
            "расходн материал","имплант","протез","стоматолог","дентальн","физиотерап");

    private static boolean passesStage1(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return STAGE1.stream().anyMatch(n::contains);
    }

    @Test
    void stage1KeepsRealDeviceNames() {
        assertThat(passesStage1("Аппарат искусственной вентиляции лёгких")).isTrue();   // ивл? нет — «вентиляц»
        assertThat(passesStage1("Рентгеновский аппарат стационарный")).isTrue();        // рентген
        assertThat(passesStage1("Аппарат УЗИ экспертного класса")).isTrue();            // узи
        assertThat(passesStage1("Наркозно-дыхательный аппарат")).isTrue();              // наркоз
        assertThat(passesStage1("Изделия медицинского назначения")).isTrue();           // медицинского назначения
    }

    @Test
    void stage1DropsGovtAndDrones() {
        assertThat(passesStage1("Государственные закупки для ГУ Аппарат акима")).isFalse();
        assertThat(passesStage1("Приобретения аппарат летательный (дрон)")).isFalse();
    }

    @Test
    void stage2FilterGatesByLots() {
        // прошёл бы ступень-1 по «хирургическ», но лоты — услуга → дроп
        assertThat(MedicalRelevanceFilter.isRelevant("Хирургическое отделение",
                List.of("Услуги по ремонту хирургического оборудования"))).isFalse();
        // лоты — медтовар → релевантен
        assertThat(MedicalRelevanceFilter.isRelevant("Закуп",
                List.of("Перчатки хирургические стерильные"))).isTrue();
    }
}
