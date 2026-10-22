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
}
