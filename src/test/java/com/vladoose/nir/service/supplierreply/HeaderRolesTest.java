package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Роли по заголовку без словаря: порядок правил решает (решение плана 13). */
class HeaderRolesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "№ п.п.|NUMBER", "№ пп|NUMBER", "Артикул (модель)|ARTICLE",
            "Наименование (описание), единица измерения|NAME", "Наименование товара|NAME", "Товары (работы, услуги)|NAME",
            "Комплектация|NAME", "Количество|QUANTITY", "Кол-во, шт.|QUANTITY", "Количество товара|QUANTITY",
            "Штук в упаковке|NOTE", "НДС|VAT", "Ставка НДС, %|VAT", "Сумма НДС|VAT", "В т.ч. НДС|VAT",
            "Цена|PRICE", "Цена со скидкой|PRICE", "Цена, руб. за ед.|PRICE", "Цена (в т.ч. НДС)|PRICE", "Стоимость за ед.|PRICE",
            "Сумма, руб.|SUM", "Сумма с НДС|SUM", "Общая сумма, тг|SUM", "Стоимость|SUM",
            "Ед.|UNIT", "Ед. изм.|UNIT", "Валюта|CURRENCY", "Примечание|NOTE", "Срок поставки|NOTE",
            "Производитель|MANUFACT", "Страна производства|IGNORE", "Регистрация в РК|REG", "Характеристики|IGNORE",
            "Сумма скидки|IGNORE", "Сумма со скидкой|SUM", "Цена без скидки|PRICE"
    })
    void heuristics(String header, LineField role) {
        assertThat(HeaderRoles.role(header, Map.of())).as(header).isEqualTo(role);
    }

    /**
     * Перенос внутри слова в заголовке («Коли-чество»; «Скид- ка» — перевод строки в ячейке стал пробелом): роль
     * «Игнорировать» — пробуем слово без переноса. Что узнаётся сейчас («Кол-во»), не меняется.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Коли-чество|QUANTITY", "Коли- чество|QUANTITY", "Коли\u00ADчество|QUANTITY", "Коли\u001Fчество|QUANTITY",
            "Наиме-нование изделия|NAME", "Сум-ма заказа|SUM", "Кол-во|QUANTITY", "Скид- ка|IGNORE", "Страна-производитель|IGNORE"
    })
    void hyphenInsideWord(String header, LineField role) {
        assertThat(HeaderRoles.role(header, Map.of())).as(header).isEqualTo(role);
    }

    /**
     * Перевод строки после дефиса («Кол-\nво», в ячейке — «Кол- во»): сначала дефис остаётся, а пробел уходит — «кол-во»;
     * и до правил, а не только после «Игнорировать»: «Кол-\nво товара» — количество, а не наименование («товар»).
     */
    @Test
    void hyphenThenLineBreak() {
        Map<String, LineField> cases = new java.util.LinkedHashMap<>();
        cases.put("Кол-\nво", LineField.QUANTITY);
        cases.put("Кол- во", LineField.QUANTITY);
        cases.put("Кол-\r\nво, шт", LineField.QUANTITY);
        cases.put("Кол-\nво товара", LineField.QUANTITY);
        cases.put("Коли-\nчество", LineField.QUANTITY);
        cases.put("Наиме-\nнование", LineField.NAME);
        cases.put("Скид-\nка", LineField.IGNORE);
        cases.forEach((h, role) -> assertThat(HeaderRoles.role(h, Map.of())).as(h).isEqualTo(role));
    }

    /** Слово без переноса ищется и в словаре оператора. */
    @Test
    void hyphenInsideWord_dictionary() {
        assertThat(HeaderRoles.role("Изде-лие", Map.of("изделие", LineField.NAME))).isEqualTo(LineField.NAME);
    }

    @Test
    void dictionaryWins_andBlankIsIgnore() {
        assertThat(HeaderRoles.role("  Цена  ", Map.of("цена", LineField.SUM))).isEqualTo(LineField.SUM);
        assertThat(HeaderRoles.role("", Map.of())).isEqualTo(LineField.IGNORE);
        assertThat(HeaderRoles.key("  Цена, ТГ ")).isEqualTo("цена, тг");
    }
}
