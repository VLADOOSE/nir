package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.offer.ClientOfferCalculator;
import com.vladoose.nir.service.offer.ItemCalc;
import com.vladoose.nir.service.offer.OfferCalculation;
import com.vladoose.nir.service.offer.OfferTotals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контрольные примеры спеки §5.5 + итоги, разбивка НДС и маржа. Правило — решение оператора 2026-10-05: наценка
 * закладывает наш НДС (цена клиенту = закупка × (100 + наценка) / 100, НДС внутри цены), НДС поставщика не учитывается
 * (себестоимость = закупка, как в его счёте). Ожидаемые числа пересчитаны отдельно, точными дробями (Python Fraction).
 */
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

    // Пример оператора: 2 000 000 + 20 % = 2 400 000 уже с нашим НДС 5 % внутри; НДС к уплате — весь НДС продажи.
    @Test
    void markupIncludesOurVat() {                               // §5.5 №1
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "2000000", "5");
        OfferCalculation calc = calculator.calculate(o);
        ItemCalc c = calc.items().get(0);
        assertThat(c.price()).isEqualByComparingTo("2400000.00");
        assertThat(c.priceNet()).isEqualByComparingTo("2285714.29");
        assertThat(c.vatSum()).isEqualByComparingTo("114285.71");
        assertThat(c.sumNet()).isEqualByComparingTo("2285714.29");
        assertThat(c.cost()).isEqualByComparingTo("2000000.00");
        assertThat(c.costTotal()).isEqualByComparingTo("2000000.00");
        assertThat(c.profit()).isEqualByComparingTo("285714.29");
        assertThat(c.markupPct()).isEqualByComparingTo("20");
        assertThat(c.effectiveVatRate()).isEqualByComparingTo("5");

        OfferTotals t = calc.totals();
        assertThat(t.sum()).isEqualByComparingTo("2400000.00");
        assertThat(t.vatTotal()).isEqualByComparingTo("114285.71");
        assertThat(t.vat()).hasSize(1);
        assertThat(t.vat().get(0).rate()).isEqualByComparingTo("5");
        assertThat(t.vat().get(0).amount()).isEqualByComparingTo("114285.71");
        assertThat(t.purchase()).isEqualByComparingTo("2000000.00");
        assertThat(t.cost()).isEqualByComparingTo("2000000.00");
        assertThat(t.revenueNet()).isEqualByComparingTo("2285714.29");
        assertThat(t.profit()).isEqualByComparingTo("285714.29");
        assertThat(t.markupAvg()).isEqualByComparingTo("20.00");
    }

    @Test
    void offerWithoutVatKeepsThePriceAndTheWholeMarkupIsProfit() {   // №2
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setVatEnabled(false);
        add(o, "2000000", "5");
        OfferCalculation calc = calculator.calculate(o);
        ItemCalc c = calc.items().get(0);
        assertThat(c.price()).isEqualByComparingTo("2400000.00");
        assertThat(c.priceNet()).isEqualByComparingTo("2400000.00");
        assertThat(c.vatSum()).isEqualByComparingTo("0");
        assertThat(c.sumNet()).isEqualByComparingTo("2400000.00");
        assertThat(c.profit()).isEqualByComparingTo("400000.00");
        assertThat(c.effectiveVatRate()).isNull();

        OfferTotals t = calc.totals();
        assertThat(t.vat()).isEmpty();
        assertThat(t.vatTotal()).isEqualByComparingTo("0");
        assertThat(t.revenueNet()).isEqualByComparingTo("2400000.00");
        assertThat(t.profit()).isEqualByComparingTo("400000.00");
        assertThat(t.markupAvg()).isEqualByComparingTo("20.00");
    }

    @Test
    void markupCountsOnPurchaseAsInvoiced() {                   // №4
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", "5");
        ItemCalc c = first(o);
        assertThat(c.cost()).isEqualByComparingTo("105000.00");
        assertThat(c.markupPct()).isEqualByComparingTo("20");
        assertThat(c.priceNet()).isEqualByComparingTo("120000.00");
        assertThat(c.price()).isEqualByComparingTo("126000.00");
        assertThat(c.vatSum()).isEqualByComparingTo("6000.00");
        assertThat(c.sumNet()).isEqualByComparingTo("120000.00");
        assertThat(c.profit()).isEqualByComparingTo("15000.00");
        assertThat(c.effectiveVatRate()).isEqualByComparingTo("5");
    }

    @Test
    void offerWithoutVat() {
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
    void lineWithoutVatInVatOffer() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", null);                                  // строка «без НДС» в КП с НДС
        ItemCalc c = first(o);
        assertThat(c.cost()).isEqualByComparingTo("105000.00");
        assertThat(c.price()).isEqualByComparingTo("126000.00");
        assertThat(c.vatSum()).isEqualByComparingTo("0");
        assertThat(c.profit()).isEqualByComparingTo("21000.00");
        assertThat(c.effectiveVatRate()).isNull();
    }

    @Test
    void manualPriceDerivesMarkupFromPurchase() {               // №3: (2 500 000 / 2 000 000 − 1) × 100
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "2000000", "5").setPriceOverride(new BigDecimal("2500000"));
        ItemCalc c = first(o);
        assertThat(c.price()).isEqualByComparingTo("2500000.00");
        assertThat(c.markupPct()).isEqualByComparingTo("25.00");
        assertThat(c.vatSum()).isEqualByComparingTo("119047.62");
        assertThat(c.profit()).isEqualByComparingTo("380952.38");
    }

    @Test
    void manualPriceDerivesMarkup() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", "5").setPriceOverride(new BigDecimal("130000"));
        ItemCalc c = first(o);
        assertThat(c.price()).isEqualByComparingTo("130000.00");
        assertThat(c.markupPct()).isEqualByComparingTo("23.81");   // 130 000 / 105 000 − 1 = 23,8095…
        assertThat(c.vatSum()).isEqualByComparingTo("6190.48");
        assertThat(c.profit()).isEqualByComparingTo("18809.52");
    }

    @Test
    void manualPriceInOfferWithoutVat() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setVatEnabled(false);
        add(o, "2000000", "5").setPriceOverride(new BigDecimal("2500000"));
        OfferCalculation calc = calculator.calculate(o);
        ItemCalc c = calc.items().get(0);
        assertThat(c.markupPct()).isEqualByComparingTo("25.00");
        assertThat(c.vatSum()).isEqualByComparingTo("0");
        assertThat(c.profit()).isEqualByComparingTo("500000.00");
        assertThat(calc.totals().vat()).isEmpty();
    }

    // Наценка ручной цены на точной «половинке»: 192 008 / 160 000 − 1 = 20,005 % → HALF_UP 20,01 (не 20,00).
    @Test
    void manualMarkupExactTieRoundsHalfUp() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "160000", "5").setPriceOverride(new BigDecimal("192008"));
        assertThat(first(o).markupPct()).isEqualByComparingTo("20.01");
    }

    @Test
    void manualPriceIgnoresOfferMarkupAndRounding() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setRounding(OfferRounding.HUNDRED);
        o.setDefaultMarkupPct(new BigDecimal("50"));
        add(o, "100", "5").setPriceOverride(new BigDecimal("1234.56"));
        assertThat(first(o).price()).isEqualByComparingTo("1234.56");
    }

    // Наценка 15 % меньше НДС 16 %: наш НДС «съедает» наценку целиком — строка в убытке (её подсвечивает экран).
    @Test
    void ownMarkupBeatsOfferMarkupAndRoundsToTens() {           // №6
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setRounding(OfferRounding.TEN);
        add(o, "12345", "16").setMarkupPct(new BigDecimal("15"));
        ItemCalc c = first(o);
        assertThat(c.price()).isEqualByComparingTo("14200.00");  // 12 345 × 1,15 = 14 196,75 → до 10
        assertThat(c.vatSum()).isEqualByComparingTo("1958.62");
        assertThat(c.markupPct()).isEqualByComparingTo("15");
        assertThat(c.profit()).isEqualByComparingTo("-103.62");
    }

    @Test
    void roundingModes() {
        // закупка 1000, наценка 17,36 % → 1 173,60 (ставка НДС строки 5 % на цену не влияет — НДС внутри)
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
        assertThat(c.costTotal()).isEqualByComparingTo("315000.00");
        assertThat(c.profit()).isEqualByComparingTo("45000.00");
    }

    @Test
    void vitaLineTotal() {                                      // №7 — КП отца от 14.09.2026
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
    void mixedVatBreakdown() {                                  // №8 — строки КП от 24.09.2026
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
        add(o, "105000", "5");                                  // 126 000, без НДС 120 000, прибыль 15 000
        add(o, "116000", "16").setMarkupPct(new BigDecimal("10")); // 127 600, без НДС 110 000 — убыток 6 000
        OfferCalculation calc = calculator.calculate(o);
        assertThat(calc.items().get(1).profit()).isEqualByComparingTo("-6000.00");
        OfferTotals t = calc.totals();
        assertThat(t.purchase()).isEqualByComparingTo("221000.00");
        assertThat(t.cost()).isEqualByComparingTo("221000.00");
        assertThat(t.revenueNet()).isEqualByComparingTo("230000.00");
        assertThat(t.vatTotal()).isEqualByComparingTo("23600.00");
        assertThat(t.profit()).isEqualByComparingTo("9000.00");
        assertThat(t.markupAvg()).isEqualByComparingTo("14.75");   // (253 600 − 221 000) / 221 000
    }

    // Средняя наценка — от закупки и по суммам С НДС: одна наценка 20 % у всех строк (5 %, 16 %, без НДС) — это 20 %,
    // а не 14,29 % (от выручки без НДС при 5 %).
    @Test
    void uniformMarkupIsTheAverageMarkup() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", "5");
        add(o, "116000", "16");
        add(o, "50000", null);
        OfferTotals t = calculator.calculate(o).totals();
        assertThat(t.sum()).isEqualByComparingTo("325200.00");
        assertThat(t.purchase()).isEqualByComparingTo("271000.00");
        assertThat(t.revenueNet()).isEqualByComparingTo("300000.00");
        assertThat(t.profit()).isEqualByComparingTo("29000.00");
        assertThat(t.markupAvg()).isEqualByComparingTo("20.00");
    }

    @Test
    void countsUnconfirmedRegistration() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "1", "5");                                        // UNCHECKED
        add(o, "1", "5").setRegistrationStatus(OfferRegistrationStatus.MANUAL);
        add(o, "1", "5").setRegistrationStatus(OfferRegistrationStatus.SUGGESTED);
        assertThat(calculator.calculate(o).totals().unconfirmedRegistrationCount()).isEqualTo(2);
    }

    // НДС поставщика не учитывается (решение оператора 2026-10-05): пометка «НДС в цене закупки» у строки — своя ставка,
    // «без НДС» или «как у продажи» с посторонней ставкой — не меняет ни цену, ни себестоимость, ни прибыль, ни итоги. КП с
    // НДС и без, ставки 5 / 16 / без НДС, наценка и ручная цена.
    @ParameterizedTest(name = "НДС закупки как у продажи = {0}, ставка закупки = {1}")
    @CsvSource(nullValues = "null", value = {"false, null", "false, 5", "false, 16", "false, 0", "true, 16"})
    void purchaseVatNeverChangesCostOrPrice(boolean same, String rate) {
        for (boolean vatEnabled : new boolean[] {true, false}) {
            ClientOffer plain = mixedOffer(vatEnabled);
            ClientOffer marked = mixedOffer(vatEnabled);
            for (ClientOfferItem it : marked.getItems()) {
                it.setPurchaseVatSame(same);
                it.setPurchaseVatRate(rate == null ? null : new BigDecimal(rate));
            }
            OfferCalculation actual = calculator.calculate(marked);
            assertThat(actual).isEqualTo(calculator.calculate(plain));
            ItemCalc c = actual.items().get(0);                  // закупка 105 000, наценка 20 %
            assertThat(c.cost()).isEqualByComparingTo("105000.00");
            assertThat(c.price()).isEqualByComparingTo("126000.00");
            assertThat(c.profit()).isEqualByComparingTo(vatEnabled ? "15000.00" : "21000.00");
            assertThat(actual.totals().purchase()).isEqualByComparingTo("376000.00");
            assertThat(actual.totals().profit()).isEqualByComparingTo(vatEnabled ? "37809.52" : "67600.00");
            assertThat(actual.totals().markupAvg()).isEqualByComparingTo("17.98");
        }
    }

    /** Закупка 105 000 (5 %), 116 000 (16 %, наценка 10 %), 50 000 (без НДС), 105 000 (5 %, ручная цена 130 000). */
    private static ClientOffer mixedOffer(boolean vatEnabled) {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setVatEnabled(vatEnabled);
        add(o, "105000", "5");
        add(o, "116000", "16").setMarkupPct(new BigDecimal("10"));
        add(o, "50000", null);
        add(o, "105000", "5").setPriceOverride(new BigDecimal("130000"));
        return o;
    }

    // Средняя наценка (javadoc OfferTotals, спека §5.4) — только по строкам с известной закупкой: выручка строки без
    // закупки в неё не входит (иначе наценка завышена); нет таких строк — null.
    @Test
    void markupAvgCountsOnlyRowsWithKnownPurchase() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", "5");                                   // 126 000, без НДС 120 000, прибыль 15 000
        add(o, null, "5").setPriceOverride(new BigDecimal("52500")); // закупки нет: без НДС 50 000, прибыль неизвестна
        OfferTotals t = calculator.calculate(o).totals();
        assertThat(t.revenueNet()).isEqualByComparingTo("170000.00");
        assertThat(t.purchase()).isEqualByComparingTo("105000.00");
        assertThat(t.cost()).isEqualByComparingTo("105000.00");
        assertThat(t.profit()).isEqualByComparingTo("15000.00");
        assertThat(t.markupAvg()).isEqualByComparingTo("20.00");    // не (178 500 − 105 000) / 105 000 = 70

        ClientOffer noCost = ClientOfferTestData.newOffer(2);
        add(noCost, null, "5").setPriceOverride(new BigDecimal("52500"));
        assertThat(calculator.calculate(noCost).totals().markupAvg()).isNull();
    }

    // Строка с закупкой 0 в среднюю наценку не входит (её наценка «—»), а её прибыль в итог прибыли — входит.
    @Test
    void markupAvgSkipsZeroPurchase() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "0", "5").setPriceOverride(new BigDecimal("1000"));   // без НДС 952,38 — вся прибыль
        add(o, "100000", "5");                                       // 120 000, без НДС 114 285,71, прибыль 14 285,71
        OfferTotals t = calculator.calculate(o).totals();
        assertThat(t.profit()).isEqualByComparingTo("15238.09");
        assertThat(t.markupAvg()).isEqualByComparingTo("20.00");    // не (121 000 − 100 000) / 100 000 = 21
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
        assertThat(calculator.calculate(o).totals().markupAvg()).isNull();
    }

    // Цена на точной «половинке» (спека §5.1–5.2: промежуточные значения — без округления, всё HALF_UP). Точная цена =
    // закупка × (100 + наценка) / 100 — ставка НДС строки в неё не входит (НДС внутри), и цена часто ровно посередине шага.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1080  | 5  | 25   | HUNDRED | 1400.00",               // 1 080 × 1,25 = 1 350 → до 100
            "1500  | 5  | 10   | HUNDRED | 1700.00",               // 1 500 × 1,10 = 1 650 → до 100
            "100   | 16 | 15   | TEN     | 120.00",                // 100 × 1,15 = 115 → до 10
            "135   | 5  | 10   | UNIT    | 149.00",                // 135 × 1,10 = 148,5 → до 1
            "12345 | 16 | 15.5 | NONE    | 14258.48",              // 12 345 × 1,155 = 14 258,475 → до 0,01
    })
    void priceExactTieRoundsHalfUp(String purchase, String vat, String markup, OfferRounding rounding, String expected) {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setRounding(rounding);
        add(o, purchase, vat).setMarkupPct(new BigDecimal(markup));
        assertThat(first(o).price()).isEqualByComparingTo(expected);
    }

    // Закупка строки на точной «половинке»: дробное количество — 1 234,57 × 0,5 = 617,285 → HALF_UP 617,29.
    @Test
    void costTotalExactTieRoundsHalfUp() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "1234.57", "5").setQuantity(new BigDecimal("0.5"));  // цена 1 234,57 × 1,2 = 1 481,484 → 1 481,48
        ItemCalc c = first(o);
        assertThat(c.costTotal()).isEqualByComparingTo("617.29");
        assertThat(c.sum()).isEqualByComparingTo("740.74");
        assertThat(c.sumNet()).isEqualByComparingTo("705.47");       // 740,74 − НДС 35,27
        assertThat(c.profit()).isEqualByComparingTo("88.18");        // прибыль + закупка строки = сумма без НДС
    }

    // «Закупка» панели маржи — сумма закупок строк (каждая до 0,01), та же, что «себестоимость» API: прибыль =
    // выручка без НДС − закупка. Округли закупку один раз в конце — две «половинки» дали бы 1 234,57, на копейку мимо.
    @Test
    void purchaseIsTheSumOfLinePurchases() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "1234.57", "5").setQuantity(new BigDecimal("0.5"));
        add(o, "1234.57", "5").setQuantity(new BigDecimal("0.5"));
        OfferTotals t = calculator.calculate(o).totals();
        assertThat(t.purchase()).isEqualByComparingTo("1234.58");
        assertThat(t.cost()).isEqualByComparingTo("1234.58");
        assertThat(t.revenueNet()).isEqualByComparingTo("1410.94");
        assertThat(t.profit()).isEqualByComparingTo("176.36");
        assertThat(t.profit()).isEqualByComparingTo(t.revenueNet().subtract(t.purchase()));
    }
}
