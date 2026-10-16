package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyRowValuesTest {

    static ReplyTable t(List<String> headers, List<LineField> roles, String currency, List<List<String>> rows) {
        List<ReplyTable.Column> cols = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) cols.add(new ReplyTable.Column(i, headers.get(i), roles.get(i)));
        List<ReplyTable.Row> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) out.add(new ReplyTable.Row(10 + i, rows.get(i)));
        return new ReplyTable("Лист1", 9, 3, currency, cols, out);
    }

    @Test
    void discountPriceWins_unitFromQuantity() {
        ReplyRowValues v = ReplyRowValues.of(t(List.of("Наименование", "Количество", "Цена", "Цена со скидкой"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.PRICE), "RUB",
                List.of(List.of("Скальпель", "1 уп", "7200", "4700")))).get(0);
        assertThat(v.price()).isEqualByComparingTo("4700");
        assertThat(v.unit()).isEqualTo("уп");
        assertThat(v.quantity().value()).isEqualByComparingTo("1");
        assertThat(v.currency()).isEqualTo("RUB");
    }

    /** «ХЭЛП»: в ячейке цены «нет», в сумме «15» — отказ, а не цена 15 (Global Constraints). */
    @Test
    void textInPriceCell_isRefusal_notComputedFromSum() {
        ReplyRowValues v = ReplyRowValues.of(t(List.of("Наименование", "Кол-во", "Цена", "Сумма", ""),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM, LineField.NOTE), null,
                List.of(List.of("Распатор", "1", "нет", "15", "Заявлены, но отсутствуют")))).get(0);
        assertThat(v.price()).isNull();
        assertThat(v.priceRefused()).isTrue();
        assertThat(v.priceComputed()).isFalse();
        assertThat(v.note()).isEqualTo("Заявлены, но отсутствуют");
        assertThat(v.participates()).isTrue();
    }

    @Test
    void blankPriceCell_computedFromSum_andNoPriceColumnUsesSum() {
        List<ReplyRowValues> a = ReplyRowValues.of(t(List.of("Наименование", "Кол-во", "Цена", "Сумма"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM), "KZT",
                List.of(List.of("Кушетка", "2 шт", "", "190 000"))));
        assertThat(a.get(0).price()).isEqualByComparingTo("95000");
        assertThat(a.get(0).priceComputed()).isTrue();

        List<ReplyRowValues> b = ReplyRowValues.of(t(List.of("Наименование", "Стоимость"),
                List.of(LineField.NAME, LineField.SUM), "KZT", List.of(List.of("Ширма", "30 000"))));
        assertThat(b.get(0).price()).isEqualByComparingTo("30000");
    }

    @Test
    void zeroSumIsNoPrice_andSectionRowDoesNotParticipate() {
        List<ReplyRowValues> v = ReplyRowValues.of(t(List.of("Наименование", "Кол-во", "Сумма"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.SUM), null,
                List.of(List.of("Спалинты", "1", "0"), List.of("Основные комплектующие:", "", ""))));
        assertThat(v.get(0).price()).isNull();
        assertThat(v.get(1).participates()).isFalse();
    }

    /**
     * КП ФОТЕК (07.10.2026): цена только «без скидки», скидка — в «Сумме со скидкой». Цена за единицу — из суммы со
     * скидкой (277 025, а не 395 750); из двух сумм со скидкой — с НДС, как в счёте поставщика.
     */
    @Test
    void onlyPriceWithoutDiscount_priceFromDiscountedSum() {
        List<String> h = List.of("№", "Товары (работы, услуги)", "Кол.", "Ед. изм.", "Цена без скидки без НДС (руб.)",
                "Сумма без скидки без НДС (руб.)", "Скид- ка", "Сумма со скидкой без НДС (руб.)", "НДС Ст. НДС",
                "НДС Сумма НДС(руб.)", "Сумма со скидкой с НДС (руб.)");
        List<LineField> roles = List.of(LineField.NUMBER, LineField.NAME, LineField.QUANTITY, LineField.UNIT, LineField.PRICE,
                LineField.SUM, LineField.IGNORE, LineField.SUM, LineField.VAT, LineField.VAT, LineField.SUM);
        List<ReplyRowValues> v = ReplyRowValues.of(t(h, roles, "RUB", List.of(
                List.of("1", "Аппарат для аспирации дыма АСД-ФОТЕК", "2", "шт", "395 750,00", "791 500,00", "30%",
                        "554 050,00", "0%", "", "554 050,00"),
                List.of("2", "Набор электродов", "1", "шт", "10 000,00", "10 000,00", "30%", "7 000,00", "20%", "1 400,00", "8 400,00"),
                List.of("3", "Доставка", "1", "шт", "5 000,00", "5 000,00", "", "5 000,00", "0%", "", "5 000,00"))));
        assertThat(v.get(0).price()).isEqualByComparingTo("277025");
        assertThat(v.get(0).priceComputed()).isTrue();
        assertThat(v.get(1).price()).isEqualByComparingTo("8400");
        assertThat(v.get(2).price()).isEqualByComparingTo("5000");   // скидки нет (сумма та же) — цена как есть, без пометки
        assertThat(v.get(2).priceComputed()).isFalse();
    }

    /** «Цена без скидки» — обычная цена: при паре колонок берётся «Цена со скидкой» (ревью ветки). */
    @Test
    void priceWithoutDiscountIsNotDiscountPrice() {
        ReplyRowValues v = ReplyRowValues.of(t(List.of("Наименование", "Цена без скидки", "Цена со скидкой"),
                List.of(LineField.NAME, LineField.PRICE, LineField.PRICE), "RUB",
                List.of(List.of("Скальпель", "7200", "4700")))).get(0);
        assertThat(v.price()).isEqualByComparingTo("4700");
    }
}
