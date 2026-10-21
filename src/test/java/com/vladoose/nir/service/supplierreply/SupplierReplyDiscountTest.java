package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.dto.response.SupplierReplyView;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyTable;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** «Скидка только в итоге» (КП «Рикты»): итог со «скидк…» меньше суммы строк; прочие итоги и округление — не скидка. */
class SupplierReplyDiscountTest {

    static List<ReplyRowValues> rows(boolean withSum, String... nameQtyPrice) {
        List<ReplyTable.Column> cols = new java.util.ArrayList<>(List.of(new ReplyTable.Column(0, "Наименование", LineField.NAME),
                new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY), new ReplyTable.Column(2, "Цена", LineField.PRICE)));
        if (withSum) cols.add(new ReplyTable.Column(3, "Сумма", LineField.SUM));
        List<ReplyTable.Row> out = new java.util.ArrayList<>();
        for (int i = 0; i < nameQtyPrice.length; i += 3) {
            BigDecimal sum = new BigDecimal(nameQtyPrice[i + 1]).multiply(new BigDecimal(nameQtyPrice[i + 2]));
            out.add(new ReplyTable.Row(5 + i / 3, withSum ? List.of(nameQtyPrice[i], nameQtyPrice[i + 1], nameQtyPrice[i + 2], sum.toPlainString())
                    : List.of(nameQtyPrice[i], nameQtyPrice[i + 1], nameQtyPrice[i + 2])));
        }
        return ReplyRowValues.of(new ReplyTable("Лист1", 4, 3, "RUB", cols, out));
    }

    static ReplyTable.Total total(String label, String amount) { return new ReplyTable.Total(label, new BigDecimal(amount)); }

    @Test
    void discountTotalBelowRows_fromPriceTimesQty() {
        SupplierReplyView.Discount d = SupplierReplyViewBuilder.discount(List.of(total("ИТОГО", "201580"),
                        total("ВСЕГО : С учетом скидки на основное оборудование 12%", "171343")),
                rows(false, "Аппарат лазерный", "2", "52800", "Аппарат магнитный", "1", "95980"));
        assertThat(d).isNotNull();
        assertThat(d.label()).startsWith("ВСЕГО : С учетом скидки");
        assertThat(d.rowsSum()).isEqualByComparingTo("201580");
    }

    @Test
    void otherTotalsAndRounding_noDiscount() {
        List<ReplyRowValues> r = rows(true, "Кушетка", "2", "95000", "Столик", "1", "48500");   // 238 500
        assertThat(SupplierReplyViewBuilder.discount(List.of(total("Итого без НДС", "212946.43")), r)).as("без НДС — не скидка").isNull();
        assertThat(SupplierReplyViewBuilder.discount(List.of(total("Итого НДС", "25553.57")), r)).isNull();
        assertThat(SupplierReplyViewBuilder.discount(List.of(total("Итого со скидкой", "238000")), r)).as("0,2 % — округление").isNull();
        assertThat(SupplierReplyViewBuilder.discount(List.of(total("Итого со скидкой 5%", "226575")), r)).isNotNull();
        assertThat(SupplierReplyViewBuilder.discount(List.of(), r)).isNull();
    }

    @Test
    void paragraphAmount_largestNumberNotPercent() {
        assertThat(ReplyTableReader.largestAmount("ВСЕГО К ОПЛАТЕ: 4 единицы товара на сумму 171 343,00 руб., НДС не облагается."))
                .isEqualByComparingTo("171343.00");
        assertThat(ReplyTableReader.largestAmount("Всего: 7 шт., скидка 25 %")).as("процент — не сумма").isEqualByComparingTo("7");
        assertThat(ReplyTableReader.largestAmount("Итого: 1 360 000 тенге, в т.ч. НДС 12% 145 714,29")).isEqualByComparingTo("1360000");
    }
}
