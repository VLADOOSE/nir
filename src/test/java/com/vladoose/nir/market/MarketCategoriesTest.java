package com.vladoose.nir.market;

import com.vladoose.nir.service.market.MarketCategories;
import com.vladoose.nir.service.market.MarketCategories.Category;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Категория по тексту (спека §9): ключевое слово без общих слов, лучшая — по совпадению слов, при равенстве — где больше договоров. */
class MarketCategoriesTest {

    @Test
    void keyword_skipsGeneric() {
        assertThat(MarketCategories.keyword("Изделие медицинское: Дефибриллятор бифазный")).isEqualTo("дефибриллятор");
        assertThat(MarketCategories.keyword("Аппарат ИВЛ")).isEqualTo("ивл");
        assertThat(MarketCategories.keyword("Набор для")).isNull();
        assertThat(MarketCategories.keyword(null)).isNull();
    }

    @Test
    void pick_byOverlapThenCount() {
        Category implanted = new Category("266011.300.000000", "Дефибриллятор", "имплантируемый кардиовертер", 40);
        Category external = new Category("266011.150.000000", "Дефибриллятор", "для терапии нарушений сердечного ритма", 20);
        assertThat(MarketCategories.pick("Дефибриллятор для терапии нарушений ритма", List.of(implanted, external))).isEqualTo(external);
        assertThat(MarketCategories.pick("Дефибриллятор", List.of(external, implanted))).as("равенство — где больше договоров").isEqualTo(implanted);
        assertThat(MarketCategories.pick("Дефибриллятор", List.of())).isNull();
    }

    @Test
    void pick_needsWordOfCategory_notOnlyExtraDescription() {
        // живая проверка 2026-10-22: «Эндоскоп» → «Услуги по обучению персонала» (49 договоров): слово было только в доп.
        // описании пункта плана («…Эндоскопическая диагностика…»), совпадений с названием и описанием категории — 0
        Category training = new Category("841311.000.000001", "Услуги по обучению персонала/сотрудников",
                "Услуги по обучению (обучению/тренинги/подготовке/переподготовке/повышению квалификации)", 49);
        assertThat(MarketCategories.pick("Эндоскоп", List.of(training))).isNull();
        // товарная «корзина» ЕНС ТРУ: слово — только в доп. описании пункта, в её названии и описании — ни одного слова текста
        Category bucket = new Category("329959.900.000001", "Изделия различные прочие", "не включенные в другие группировки", 120);
        assertThat(MarketCategories.pick("Эндоскоп", List.of(bucket))).isNull();
    }

    @Test
    void pick_goodsSkipServiceCategories_serviceTextKeepsThem() {
        Category repair = new Category("331312.100.000000", "Работы по ремонту/модернизации медицинского оборудования",
                "тележек медицинских", 30);
        Category cart = new Category("309911.100.000001", "Тележка", "медицинская для перевозки", 5);
        assertThat(MarketCategories.pick("Тележка медицинская", List.of(repair, cart))).isEqualTo(cart);
        assertThat(MarketCategories.relevant("Тележка медицинская", List.of(repair, cart))).containsExactly(cart);
        assertThat(MarketCategories.pick("Ремонт медицинского оборудования", List.of(repair, cart))).isEqualTo(repair);
        // тара для медотходов — товар, хотя в названии «утилизации отходов»
        Category kbu = new Category("222219.000.000002", "Контейнер для сбора и утилизации медицинских отходов", "КБУ", 3);
        assertThat(MarketCategories.relevant("Контейнер КБУ", List.of(kbu))).containsExactly(kbu);
    }

    /** Живая проверка 2026-10-22: «Тележка» уходила в «Чемодан-тележку» — при равном совпадении сперва категория с тем же главным словом. */
    @Test
    void pick_sameMainWordBeforeCount() {
        Category suitcase = new Category("151212.100.000017", "Чемодан-тележка", "из пластмассы", 3);
        Category cart = new Category("325030.500.000010", "Тележка", "медицинская", 1);
        assertThat(MarketCategories.pick("Тележка", List.of(suitcase, cart))).isEqualTo(cart);
    }

    /**
     * Живая проверка 2026-10-22 на товарах сайта: слова сравнивались по первым 5 буквам — «электрохирургический» = «электрод»,
     * «интерпретацией» = «интернет», «комплектация» = «комплектующие»; теперь слово целиком (окончание до 3 букв), и главное слово
     * категории должно быть в тексте.
     */
    @Test
    void pick_wholeWords_andMainWordOfCategoryInText() {
        Category electrode = new Category("325013.700.000020", "Электрод", "нейтральный, электрохирургический, одноразовый", 1);
        assertThat(MarketCategories.pick("ВЧ электрохирургический блок для аппарата ЭХВЧ-350-01-\"ФОТЕК\"", List.of(electrode))).isNull();
        Category suction = new Category("325050.900.000026", "Отсасыватель", "для аспирации жидкого содержимого", 41);
        assertThat(MarketCategories.pick("Блок управления ACTITON-A (с режимом аспирации жидкости)", List.of(suction))).isNull();
        assertThat(MarketCategories.pick("Отсасыватель медицинский портативный ОМП-5/80", List.of(suction))).isEqualTo(suction);
        Category parts = new Category("266012.100.000000", "Медицинские изделия", "Комплектующие для аппарата Электрокардиограф BTL", 2);
        Category ecg = new Category("266012.100.000002", "Электрокардиограф", "12-канальный, встроенный термопринтер", 46);
        Category telemetry = new Category("266012.100.000005", "Электрокардиограф", "12-канальный, с функцией интернет-телеметрии", 11);
        assertThat(MarketCategories.pick("Электрокардиограф ЭК1Т-1/3-07 «Аксион» — простая комплектация", List.of(parts, telemetry, ecg)))
                .isEqualTo(ecg);
        assertThat(MarketCategories.pick("Электрокардиограф ЭК3ТЦ-3/6-04 «Аксион» — комплектация с интерпретацией", List.of(telemetry, ecg)))
                .isEqualTo(ecg);
        Category balloon = new Category("325021.800.000008", "Баллон", "кислородный", 3);
        assertThat(MarketCategories.pick("Аппарат искусственной вентиляции легких для экстренной помощи А-ИВЛ-Э", List.of(balloon))).isNull();
        // падежи и окончания — одно слово
        assertThat(MarketCategories.pick("Отсасывателя медицинского", List.of(suction))).isEqualTo(suction);
    }
}
