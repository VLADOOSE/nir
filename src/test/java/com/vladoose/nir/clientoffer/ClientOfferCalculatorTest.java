package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.offer.ClientOfferCalculator;
import com.vladoose.nir.service.offer.ItemCalc;
import com.vladoose.nir.service.offer.OfferCalculation;
import com.vladoose.nir.service.offer.OfferTotals;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Контрольные примеры спеки §5.5 + итоги, разбивка НДС и маржа. */
class ClientOfferCalculatorTest {

    private final ClientOfferCalculator calculator = new ClientOfferCalculator();

    private static ClientOfferItem add(ClientOffer o, String purchase, String vat) {
        ClientOfferItem it = ClientOfferTestData.item(o, o.getItems().size() + 1, "Позиция " + (o.getItems().size() + 1), purchase, vat);
        o.getItems().add(it);
        return it;
    }

    private ItemCalc first(ClientOffer o) {
        return calculator.calculate(o).items().get(0);
    }

    @Test
    void markupCountsOnPriceWithoutVat() {                     // §5.5 №1
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", "5");
        ItemCalc c = first(o);
        assertThat(c.cost()).isEqualByComparingTo("100000.00");
        assertThat(c.markupPct()).isEqualByComparingTo("20");
        assertThat(c.priceNet()).isEqualByComparingTo("120000.00");
        assertThat(c.price()).isEqualByComparingTo("126000.00");
        assertThat(c.vatSum()).isEqualByComparingTo("6000.00");
        assertThat(c.sumNet()).isEqualByComparingTo("120000.00");
        assertThat(c.profit()).isEqualByComparingTo("20000.00");
        assertThat(c.effectiveVatRate()).isEqualByComparingTo("5");
    }

    @Test
    void supplierWithoutVat() {                                 // №2
        ClientOffer o = ClientOfferTestData.newOffer(1);
        ClientOfferItem it = add(o, "100000", "5");
        it.setPurchaseVatSame(false);
        it.setPurchaseVatRate(null);
        ItemCalc c = first(o);
        assertThat(c.cost()).isEqualByComparingTo("100000.00");
        assertThat(c.price()).isEqualByComparingTo("126000.00");
        assertThat(c.profit()).isEqualByComparingTo("20000.00");
    }

    @Test
    void offerWithoutVatKeepsInputVatInCost() {                 // №3
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setVatEnabled(false);
        add(o, "105000", "5");
        ItemCalc c = first(o);
        assertThat(c.cost()).isEqualByComparingTo("105000.00");
        assertThat(c.price()).isEqualByComparingTo("126000.00");
        assertThat(c.vatSum()).isEqualByComparingTo("0");
        assertThat(c.profit()).isEqualByComparingTo("21000.00");
        assertThat(c.effectiveVatRate()).isNull();
    }

    @Test
    void lineWithoutVatInVatOfferAlsoKeepsInputVat() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", null);                                  // строка «без НДС» в КП с НДС
        ItemCalc c = first(o);
        assertThat(c.cost()).isEqualByComparingTo("105000.00");
        assertThat(c.price()).isEqualByComparingTo("126000.00");
        assertThat(c.effectiveVatRate()).isNull();
    }

    @Test
    void manualPriceDerivesMarkup() {                           // №4
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", "5").setPriceOverride(new BigDecimal("130000"));
        ItemCalc c = first(o);
        assertThat(c.price()).isEqualByComparingTo("130000.00");
        assertThat(c.markupPct()).isEqualByComparingTo("23.81");
        assertThat(c.vatSum()).isEqualByComparingTo("6190.48");
        assertThat(c.profit()).isEqualByComparingTo("23809.52");
    }

    @Test
    void manualPriceIgnoresOfferMarkupAndRounding() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setRounding(OfferRounding.HUNDRED);
        o.setDefaultMarkupPct(new BigDecimal("50"));
        add(o, "100", "5").setPriceOverride(new BigDecimal("1234.56"));
        assertThat(first(o).price()).isEqualByComparingTo("1234.56");
    }

    @Test
    void ownMarkupBeatsOfferMarkupAndRoundsToTens() {           // №5
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setRounding(OfferRounding.TEN);
        add(o, "12345", "16").setMarkupPct(new BigDecimal("15"));
        ItemCalc c = first(o);
        assertThat(c.price()).isEqualByComparingTo("14200.00");  // 14 196,75 → до 10
        assertThat(c.vatSum()).isEqualByComparingTo("1958.62");
        assertThat(c.markupPct()).isEqualByComparingTo("15");
    }

    @Test
    void roundingModes() {
        // закупка 1000 с НДС 5%, наценка 17,36% → 1173,60
        assertThat(priceWith(OfferRounding.NONE)).isEqualByComparingTo("1173.60");
        assertThat(priceWith(OfferRounding.UNIT)).isEqualByComparingTo("1174.00");
        assertThat(priceWith(OfferRounding.TEN)).isEqualByComparingTo("1170.00");
        assertThat(priceWith(OfferRounding.HUNDRED)).isEqualByComparingTo("1200.00");
    }

    private BigDecimal priceWith(OfferRounding rounding) {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setRounding(rounding);
        o.setDefaultMarkupPct(new BigDecimal("17.36"));
        add(o, "1000", "5");
        return first(o).price();
    }

    @Test
    void quantityMultipliesSums() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", "5").setQuantity(new BigDecimal("3"));
        ItemCalc c = first(o);
        assertThat(c.sum()).isEqualByComparingTo("378000.00");
        assertThat(c.vatSum()).isEqualByComparingTo("18000.00");
        assertThat(c.costTotal()).isEqualByComparingTo("300000.00");
        assertThat(c.profit()).isEqualByComparingTo("60000.00");
    }

    @Test
    void vitaLineTotal() {                                      // №6 — КП отца от 14.09.2026
        ClientOffer o = ClientOfferTestData.newOffer(443);
        String[] prices = {"7650000.00", "1950000.00", "15045.02", "15045.02", "9144.58", "10552.51", "6858.43", "6321.41",
                "9979.20", "7809.22", "91808.64", "14304.10", "15462.05", "10728.10", "70631.23", "14411.52", "4147.20"};
        for (String p : prices) add(o, null, "5").setPriceOverride(new BigDecimal(p));
        OfferTotals t = calculator.calculate(o).totals();
        assertThat(t.sum()).isEqualByComparingTo("9902248.23");
        assertThat(t.vat()).hasSize(1);
        assertThat(t.vat().get(0).rate()).isEqualByComparingTo("5");
        assertThat(t.vat().get(0).amount()).isEqualByComparingTo("471535.63");
        assertThat(t.itemCount()).isEqualTo(17);
        assertThat(t.noPurchaseCount()).isEqualTo(17);
    }

    @Test
    void mixedVatBreakdown() {                                  // №7 — строки КП от 24.09.2026
        ClientOffer o = ClientOfferTestData.newOffer(1);
        line(o, "105600.00", 3, "5");
        line(o, "13515.00", 4, "16");
        line(o, "21150.00", 2, "5");
        line(o, "83725.00", 4, "16");
        OfferTotals t = calculator.calculate(o).totals();
        assertThat(t.sum()).isEqualByComparingTo("748060.00");
        assertThat(t.vat()).extracting(v -> v.rate().stripTrailingZeros().toPlainString()).containsExactly("5", "16");
        assertThat(t.vat().get(0).amount()).isEqualByComparingTo("17100.00");
        assertThat(t.vat().get(1).amount()).isEqualByComparingTo("53649.65");
        assertThat(t.vatTotal()).isEqualByComparingTo("70749.65");
    }

    private static void line(ClientOffer o, String price, int qty, String vat) {
        ClientOfferItem it = add(o, null, vat);
        it.setPriceOverride(new BigDecimal(price));
        it.setQuantity(BigDecimal.valueOf(qty));
    }

    @Test
    void sectionAndIncludedRowsAreNotCalculated() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        ClientOfferItem section = add(o, null, null);
        section.setKind(ClientOfferItemKind.SECTION);
        add(o, "105000", "5");
        ClientOfferItem included = add(o, null, null);
        included.setKind(ClientOfferItemKind.INCLUDED);
        OfferCalculation calc = calculator.calculate(o);
        assertThat(calc.items()).hasSize(3);
        assertThat(calc.items().get(0)).isSameAs(ItemCalc.NONE);
        assertThat(calc.items().get(2)).isSameAs(ItemCalc.NONE);
        assertThat(calc.totals().itemCount()).isEqualTo(1);
        assertThat(calc.totals().sum()).isEqualByComparingTo("126000.00");
    }

    @Test
    void rowWithoutPurchaseAndPriceHasNoSum() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, null, "5");
        OfferCalculation calc = calculator.calculate(o);
        assertThat(calc.items().get(0).price()).isNull();
        assertThat(calc.items().get(0).sum()).isNull();
        assertThat(calc.totals().sum()).isEqualByComparingTo("0");
        assertThat(calc.totals().noPurchaseCount()).isEqualTo(1);
    }

    @Test
    void marginTotals() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", "5");                                  // прибыль 20 000 на себестоимость 100 000
        add(o, "116000", "16").setMarkupPct(new BigDecimal("10")); // себестоимость 100 000, прибыль 10 000
        OfferTotals t = calculator.calculate(o).totals();
        assertThat(t.purchase()).isEqualByComparingTo("221000.00");
        assertThat(t.cost()).isEqualByComparingTo("200000.00");
        assertThat(t.revenueNet()).isEqualByComparingTo("230000.00");
        assertThat(t.profit()).isEqualByComparingTo("30000.00");
        assertThat(t.markupAvg()).isEqualByComparingTo("15.00");
    }

    @Test
    void countsUnconfirmedRegistration() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "1", "5");                                        // UNCHECKED
        add(o, "1", "5").setRegistrationStatus(OfferRegistrationStatus.MANUAL);
        add(o, "1", "5").setRegistrationStatus(OfferRegistrationStatus.SUGGESTED);
        assertThat(calculator.calculate(o).totals().unconfirmedRegistrationCount()).isEqualTo(2);
    }

    // Входной НДС поставщика задан ЯВНО, а не «как у продажи». В двух тестах «без НДС» выше p наследует null от v,
    // и гард «вычитаем входной НДС, только если продаём с НДС» ими не проверяется: без этих двух мутация
    // «себестоимость всегда без входного НДС» проходит зелёной.
    @Test
    void offerWithoutVatKeepsExplicitInputVatInCost() {          // №3: закупка 105 000 с НДС 5% у поставщика
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setVatEnabled(false);
        ClientOfferItem it = add(o, "105000", "5");
        it.setPurchaseVatSame(false);
        it.setPurchaseVatRate(new BigDecimal("5"));
        ItemCalc c = first(o);
        assertThat(c.cost()).isEqualByComparingTo("105000.00");
        assertThat(c.price()).isEqualByComparingTo("126000.00");
        assertThat(c.vatSum()).isEqualByComparingTo("0");
        assertThat(c.profit()).isEqualByComparingTo("21000.00");
        assertThat(c.effectiveVatRate()).isNull();
    }

    @Test
    void lineWithoutVatKeepsExplicitInputVatInCost() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        ClientOfferItem it = add(o, "105000", null);             // строка «без НДС» в КП с НДС
        it.setPurchaseVatSame(false);
        it.setPurchaseVatRate(new BigDecimal("5"));
        ItemCalc c = first(o);
        assertThat(c.cost()).isEqualByComparingTo("105000.00");
        assertThat(c.price()).isEqualByComparingTo("126000.00");
        assertThat(c.profit()).isEqualByComparingTo("21000.00");
        assertThat(c.effectiveVatRate()).isNull();
    }

    // Средняя наценка (javadoc OfferTotals, спека §5.4) — прибыль / себестоимость только по строкам, где себестоимость
    // известна: выручка строки без закупки в неё не входит (иначе наценка завышена); нет таких строк — null.
    @Test
    void markupAvgCountsOnlyRowsWithKnownCost() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", "5");                                   // себестоимость 100 000, без НДС 120 000, прибыль 20 000
        add(o, null, "5").setPriceOverride(new BigDecimal("52500")); // закупки нет: без НДС 50 000, прибыль неизвестна
        OfferTotals t = calculator.calculate(o).totals();
        assertThat(t.revenueNet()).isEqualByComparingTo("170000.00");
        assertThat(t.cost()).isEqualByComparingTo("100000.00");
        assertThat(t.profit()).isEqualByComparingTo("20000.00");
        assertThat(t.markupAvg()).isEqualByComparingTo("20.00");    // не (170 000 − 100 000) / 100 000 = 70

        ClientOffer noCost = ClientOfferTestData.newOffer(2);
        add(noCost, null, "5").setPriceOverride(new BigDecimal("52500"));
        assertThat(calculator.calculate(noCost).totals().markupAvg()).isNull();
    }

    // Спека §5.1 п.3: закупка 0 и ручная цена — наценка «—» (null), а не деление на ноль (ArithmeticException → 500
    // при чтении такого КП).
    @Test
    void manualPriceWithZeroPurchaseHasNoMarkup() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "0", "5").setPriceOverride(new BigDecimal("1000"));
        ItemCalc c = first(o);
        assertThat(c.markupPct()).isNull();
        assertThat(c.price()).isEqualByComparingTo("1000.00");
        assertThat(c.vatSum()).isEqualByComparingTo("47.62");
        assertThat(c.cost()).isEqualByComparingTo("0.00");
        assertThat(c.profit()).isEqualByComparingTo("952.38");
    }
}
