package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.productwatch.ProductFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Требования ТЗ из настоящих ТЗ набора (спека §5): склейка строк, «;» и столбик, «не требования». */
class SpecRequirementsTest {

    private static List<SpecRequirement> of(String file) {
        return SpecRequirements.extract(SpecRequirements.body(ProductFixtures.spec(file)));
    }

    /** Аспиратор Мангистау 17729514-1: требования через «;», строки PDF разорваны посередине. */
    @Test
    void aspirator_semicolonsAndBrokenLines() {
        List<SpecRequirement> r = of("17729514-1_87973751-ОИ4.pdf");
        assertThat(r).extracting(SpecRequirement::kind).contains(SpecRequirement.Kind.NUMBER, SpecRequirement.Kind.FEATURE);
        SpecRequirement flow = r.stream().filter(x -> x.unit() == SpecUnit.L_MIN).findFirst().orElseThrow();
        assertThat(flow.cmp()).isEqualTo(SpecCmp.RANGE);
        assertThat(flow.lo()).isEqualTo(15.0);
        assertThat(flow.hi()).isEqualTo(30.0);
        SpecRequirement vacuum = r.stream().filter(x -> x.unit() == SpecUnit.KPA).findFirst().orElseThrow();
        assertThat(vacuum.hi()).isEqualTo(75.0);
        assertThat(r).extracting(SpecRequirement::text).anyMatch(t -> t.contains("защиты от переполнения"));
        // назначение и область применения — не требования
        assertThat(r).extracting(SpecRequirement::text).noneMatch(t -> t.toLowerCase().contains("предназначен"));
        assertThat(r).extracting(SpecRequirement::text).noneMatch(t -> t.toLowerCase().contains("может применяться"));
    }

    /** Облучатель Абай 17822225-1: столбик «параметр — значение»; счётное «Количество ламп — 1». */
    @Test
    void irradiator_columnsAndCount() {
        List<SpecRequirement> r = of("17822225-1_88296583-ЗЦП1.pdf");
        SpecRequirement lamps = r.stream().filter(x -> x.name().contains("количество ламп")).findFirst().orElseThrow();
        assertThat(lamps.kind()).isEqualTo(SpecRequirement.Kind.COUNT);
        assertThat(lamps.lo()).isEqualTo(1.0);
        SpecRequirement power = r.stream().filter(x -> x.unit() == SpecUnit.W).findFirst().orElseThrow();
        assertThat(power.name()).contains("мощность лампы");
        assertThat(power.cmp()).isEqualTo(SpecCmp.APPROX);
        assertThat(r).extracting(SpecRequirement::text).anyMatch(t -> t.contains("настенно-потолочный"));
        assertThat(r).extracting(SpecRequirement::text).noneMatch(t -> t.toLowerCase().contains("места поставки"));
    }

    /** Review Focus #1: несколько чисел в одном пункте — каждое своё требование со своим именем. */
    @Test
    void severalNumbersInOneClause() {
        List<SpecRequirement> r = SpecRequirements.extract("Габаритные размеры блока не более 295х295х100 мм, масса не более 4,5 кг; "
                + "Питание 220 В, 50 Гц");
        assertThat(r).hasSize(4);
        assertThat(r.get(0).kind()).isEqualTo(SpecRequirement.Kind.DIMS);
        assertThat(r.get(1).name()).contains("масса");
        assertThat(r.get(1).unit()).isEqualTo(SpecUnit.KG);
        assertThat(r.get(2).unit()).isEqualTo(SpecUnit.V);
        assertThat(r.get(3).unit()).isEqualTo(SpecUnit.HZ);
        assertThat(r.get(3).name()).contains("питание");    // имя — от начала пункта, если перед числом пусто
    }

    /** Заголовок «Основные технические характеристики:» перед пунктом — не часть признака. */
    @Test
    void headerPrefixDropped() {
        List<SpecRequirement> r = SpecRequirements.extract("Основные технические характеристики: работа от электрической сети");
        assertThat(r).singleElement().satisfies(x -> {
            assertThat(x.kind()).isEqualTo(SpecRequirement.Kind.FEATURE);
            assertThat(x.text()).isEqualTo("работа от электрической сети");
        });
    }

    @Test
    void emptyAndTooShort() {
        assertThat(SpecRequirements.extract("")).isEmpty();
        assertThat(SpecRequirements.extract("медицинский")).isEmpty();
        assertThat(SpecRequirements.body(null)).isEmpty();
    }

    /** Имя параметра — без «не менее и не более», связок и скобок. */
    @Test
    void nameWithoutConditionWords() {
        assertThat(SpecRequirements.extract("Ширина: не менее и не более - 430 мм")).singleElement().satisfies(x -> {
            assertThat(x.name()).isEqualTo("ширина");
            assertThat(x.text()).as("условие — как в ТЗ").isEqualTo("ширина не менее и не более - 430 мм");
        });
        assertThat(SpecRequirements.extract("(допустимое отклонение ± 10 %")).singleElement()
                .satisfies(x -> assertThat(x.name()).isEqualTo("допустимое отклонение"));
    }

    /** «Назначение: …» — не требование, даже с числом (BOWA: «для пациентов с массой тела более 15 кг»). */
    @Test
    void purposeIsNotRequirement() {
        assertThat(SpecRequirements.extract("Назначение: для взрослых пациентов с массой тела более 15 кг")).isEmpty();
    }

    /** «Мощность:» и значение строкой ниже — один пункт; номера списка «1)» в имя не попадают. */
    @Test
    void valueOnNextLine_andNumberedList() {
        assertThat(SpecRequirements.extract(SpecRequirements.reflow("Мощность:\n400 Вт"))).singleElement().satisfies(x -> {
            assertThat(x.name()).isEqualTo("мощность");
            assertThat(x.unit()).isEqualTo(SpecUnit.W);
        });
        assertThat(SpecRequirements.extract("1) Мощность 400 Вт 2) масса 5 кг"))
                .extracting(SpecRequirement::name).containsExactly("мощность", "масса");
    }

    /** Число в начале пункта — содержание, а не номер списка: «5 электродов» не теряет «5»; «1.», «2)», «•» — номера. */
    @Test
    void leadingNumberIsContent_listMarkersDropped() {
        assertThat(SpecRequirements.extract("5 электродов в комплекте")).singleElement()
                .satisfies(x -> assertThat(x.text()).isEqualTo("5 электродов в комплекте"));
        assertThat(SpecRequirements.extract("1.2. Наличие защиты от перегрева")).singleElement()
                .satisfies(x -> assertThat(x.text()).isEqualTo("Наличие защиты от перегрева"));
        assertThat(SpecRequirements.extract("• наличие режима ожидания")).singleElement()
                .satisfies(x -> assertThat(x.text()).isEqualTo("наличие режима ожидания"));
    }

    /**
     * Абзац без «;» (отсасыватель 17705188-1): «Поставщик должен представить» в конце не выбрасывает габариты и массу
     * перед ним — пункт обрезается по началу «не требования».
     */
    @Test
    void runOnParagraph_cutAtNonRequirement() {
        List<SpecRequirement> r = SpecRequirements.extract(SpecRequirements.reflow(
                "Габариты (Ш*Г*В): 180*240*450 мм Размеры упаковки (Ш*Г*В):\n570*420*220 мм Масса: 3 кг Вес брутто: 4,12 кг На момент\n"
                        + "поставки Поставщик должен представить: 1. Подтверждающий\nдокумент о внесении оборудования"));
        assertThat(r).filteredOn(x -> x.kind() == SpecRequirement.Kind.DIMS).first().satisfies(x -> {
            assertThat(x.name()).isEqualTo("габариты (ш*г*в)");
            assertThat(x.dims()).containsExactly(450.0, 240.0, 180.0);
        });
        assertThat(r).filteredOn(x -> x.name().equals("масса")).singleElement()
                .satisfies(x -> assertThat(x.hi()).isEqualTo(3000.0));
        assertThat(r).extracting(SpecRequirement::text).noneMatch(t -> t.toLowerCase().contains("поставщик"));
        assertThat(r).extracting(SpecRequirement::text).noneMatch(t -> t.toLowerCase().contains("документ"));
    }

    /** Номер строки таблицы «2 Устройство…» — не имя; число без параметра («-1 ШТ») — не требование. */
    @Test
    void rowNumberDropped_numberWithoutParameterSkipped() {
        assertThat(SpecRequirements.extract("2 Устройство активирующее, исполнение 2 - 1шт")).singleElement()
                .satisfies(x -> assertThat(x.name()).isEqualTo("устройство активирующее, исполнение 2"));
        assertThat(SpecRequirements.extract("-1 ШТ")).isEmpty();
        assertThat(SpecRequirements.extract(SpecRequirements.reflow("Комплектация:\n2 Электрода"))).isEmpty();
    }

    /** «Регистрация ЭКГ» — функция прибора; «регистрационное удостоверение» — документ, не требование. */
    @Test
    void ecgRecordingIsNotRegistrationCertificate() {
        assertThat(SpecRequirements.extract("Электрокардиограф с регистрацией ЭКГ в ручном и автоматическом режимах")).hasSize(1);
        assertThat(SpecRequirements.extract("Наличие регистрационного удостоверения в РК")).isEmpty();
    }
}
