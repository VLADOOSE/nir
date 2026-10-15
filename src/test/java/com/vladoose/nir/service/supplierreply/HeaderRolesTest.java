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
            "Производитель|MANUFACT", "Страна производства|IGNORE", "Регистрация в РК|IGNORE", "Характеристики|IGNORE"
    })
    void heuristics(String header, LineField role) {
        assertThat(HeaderRoles.role(header, Map.of())).as(header).isEqualTo(role);
    }

    @Test
    void dictionaryWins_andBlankIsIgnore() {
        assertThat(HeaderRoles.role("  Цена  ", Map.of("цена", LineField.SUM))).isEqualTo(LineField.SUM);
        assertThat(HeaderRoles.role("", Map.of())).isEqualTo(LineField.IGNORE);
        assertThat(HeaderRoles.key("  Цена, ТГ ")).isEqualTo("цена, тг");
    }
}
