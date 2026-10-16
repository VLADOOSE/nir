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

    /** Ревью ветки курса: «Цена без скидки, с НДС» не равна «Цене, с НДС» — из двух берётся цена со скидкой (как в main). */
    @Test
    void priceWithoutDiscountWithVat_losesToPriceWithVat() {
        ReplyRowValues v = ReplyRowValues.of(t(List.of("Наименование", "Кол-во", "Цена без скидки, с НДС", "Сумма без скидки, с НДС",
                        "Скидка, %", "Цена, с НДС", "Сумма, с НДС"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM, LineField.IGNORE, LineField.PRICE, LineField.SUM),
                "KZT", List.of(List.of("Набор", "2", "100 000", "200 000", "30", "70 000", "140 000")))).get(0);
        assertThat(v.price()).isEqualByComparingTo("70000");
        assertThat(v.sum()).isEqualByComparingTo("140000");
    }

    /** «Цена до скидки» — тоже цена без скидки: «Цена со скидкой» правее её всё равно перебивает. */
    @Test
    void priceBeforeDiscountIsNotDiscountPrice() {
        ReplyRowValues v = ReplyRowValues.of(t(List.of("Наименование", "Цена до скидки", "Цена со скидкой"),
                List.of(LineField.NAME, LineField.PRICE, LineField.PRICE), "RUB",
                List.of(List.of("Скальпель", "7200", "4700")))).get(0);
        assertThat(v.price()).isEqualByComparingTo("4700");
    }

    /**
     * Правило «Сумма со скидкой ÷ количество» — только при распознанном количестве и в разумных пределах: без
     * количества сумма всей строки стала бы ценой за штуку (ревью ветки).
     */
    @Test
    void discountRule_needsQuantity_andStaysNearPrice() {
        List<String> h = List.of("Наименование", "Цена без скидки", "Сумма со скидкой");
        List<LineField> roles = List.of(LineField.NAME, LineField.PRICE, LineField.SUM);
        // без количества сумма может быть и за несколько штук — не угадываем, даже если она похожа на цену со скидкой
        ReplyRowValues noQty = ReplyRowValues.of(t(h, roles, "RUB", List.of(List.of("Набор", "395 750", "300 000")))).get(0);
        assertThat(noQty.price()).isEqualByComparingTo("395750");
        assertThat(noQty.priceComputed()).isFalse();

        ReplyRowValues tooHigh = ReplyRowValues.of(t(List.of("Наименование", "Кол-во", "Цена без скидки", "Сумма со скидкой"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM), "RUB",
                List.of(List.of("Набор", "1", "395 750", "1 000 000")))).get(0);
        assertThat(tooHigh.price()).isEqualByComparingTo("395750");
    }

    /** Размер скидки («Скидка (сумма)») — не сумма со скидкой: ценой не становится. */
    @Test
    void discountAmountIsNotDiscountedSum() {
        ReplyRowValues v = ReplyRowValues.of(t(List.of("Наименование", "Кол-во", "Цена без скидки", "Скидка (сумма)", "Сумма"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM, LineField.SUM), "RUB",
                List.of(List.of("Набор", "1", "395 750", "118 725", "277 025")))).get(0);
        assertThat(v.price()).isEqualByComparingTo("395750");
        assertThat(v.sum()).isEqualByComparingTo("277025");   // сумма строки — «Сумма», не размер скидки левее неё
    }

    /** «Стоимость со скидкой» бывает ценой за единицу — делить её на количество нельзя (ревью ветки). */
    @Test
    void costWithDiscountIsNotDiscountedSum() {
        ReplyRowValues v = ReplyRowValues.of(t(List.of("Наименование", "Кол-во", "Цена без скидки", "Стоимость со скидкой"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM), "RUB",
                List.of(List.of("Набор", "3", "395 750", "277 025")))).get(0);
        assertThat(v.price()).isEqualByComparingTo("395750");
    }

    /** Частая форма 1С «Цена | Скидка | Сумма со скидкой»: цена за единицу — из суммы со скидкой; без скидки — как есть. */
    @Test
    void plainPriceWithDiscountedSum_1cForm() {
        List<ReplyRowValues> v = ReplyRowValues.of(t(List.of("Наименование", "Кол-во", "Цена", "Скидка", "Сумма со скидкой"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.IGNORE, LineField.SUM), "RUB",
                List.of(List.of("Скальпель", "2", "7 200", "35%", "9 360"), List.of("Пинцет", "1", "1 000", "", "1 000"))));
        assertThat(v.get(0).price()).isEqualByComparingTo("4680");
        assertThat(v.get(0).priceComputed()).isTrue();
        assertThat(v.get(1).price()).isEqualByComparingTo("1000");
        assertThat(v.get(1).priceComputed()).isFalse();
    }

    /** Сумма для сверки «сумма ≠ цена × кол-во» — с той же ставкой НДС, что у колонки цены (иначе ложная тревога). */
    @Test
    void sumForCheckHasSameVatAsPrice() {
        ReplyRowValues v = ReplyRowValues.of(t(List.of("Наименование", "Кол-во", "Цена со скидкой без НДС", "Сумма со скидкой без НДС",
                        "Сумма со скидкой с НДС"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM, LineField.SUM), "KZT",
                List.of(List.of("Скальпель", "2", "4 700", "9 400", "10 528")))).get(0);
        assertThat(v.price()).isEqualByComparingTo("4700");
        assertThat(v.sum()).isEqualByComparingTo("9400");
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
