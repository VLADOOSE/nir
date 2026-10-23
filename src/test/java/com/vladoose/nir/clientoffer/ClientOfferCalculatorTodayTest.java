package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.offer.ClientOfferCalculator;
import com.vladoose.nir.service.offer.OfferTotals;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Закупка и прибыль по курсу сегодня (спека 2026-10-23 §4.5): цена клиенту — от нашего курса, итоги «сегодня» — рядом. */
class ClientOfferCalculatorTodayTest {

    private final ClientOfferCalculator calc = new ClientOfferCalculator();

    private static ClientOffer offer() {
        return ClientOffer.builder().market(Market.KZ).vatEnabled(true).defaultMarkupPct(new BigDecimal("80"))
                .rounding(OfferRounding.NONE).origin(ClientOfferOrigin.BUILDER).build();
    }

    private static ClientOfferItem rub(ClientOffer o, String original, String rate, int qty) {
        BigDecimal purchase = new BigDecimal(original).multiply(new BigDecimal(rate)).setScale(2, java.math.RoundingMode.HALF_UP);
        return ClientOfferItem.builder().offer(o).lineNo(1).kind(ClientOfferItemKind.ITEM).name("ДКИ-Н-12").unit("шт")
                .quantity(BigDecimal.valueOf(qty)).purchasePrice(purchase).purchaseCurrency("RUB")
                .purchaseOriginal(new BigDecimal(original)).purchaseRate(new BigDecimal(rate)).vatRate(new BigDecimal("16")).build();
    }

    private static ClientOfferItem tenge(ClientOffer o, String purchase) {
        return ClientOfferItem.builder().offer(o).lineNo(2).kind(ClientOfferItemKind.ITEM).name("Доставка").unit("шт")
                .quantity(BigDecimal.ONE).purchasePrice(new BigDecimal(purchase)).vatRate(new BigDecimal("16")).build();
    }

    @Test
    void today_onlyForeignLinesConvertedAtTodayRate() {
        ClientOffer o = offer();
        o.getItems().add(rub(o, "150000", "6", 1));      // закупка 900 000 по нашему курсу
        o.getItems().add(tenge(o, "50000"));
        OfferTotals t = calc.calculate(o, Map.of("RUB", new BigDecimal("5.48"))).totals();
        assertThat(t.purchase()).isEqualByComparingTo("950000.00");
        assertThat(t.purchaseToday()).isEqualByComparingTo("872000.00");   // 822 000 + 50 000
        // цена клиенту — от закупки по нашему курсу: прибыль сегодня = прибыль + разница закупок
        assertThat(t.profitToday().subtract(t.profit())).isEqualByComparingTo("78000.00");
    }

    @Test
    void noTodayRate_todayTotalsEmpty() {
        ClientOffer o = offer();
        o.getItems().add(rub(o, "150000", "6", 2));
        assertThat(calc.calculate(o, Map.of()).totals().purchaseToday()).isNull();
        assertThat(calc.calculate(o, Map.of()).totals().profitToday()).isNull();
    }

    @Test
    void noForeignLines_todayTotalsEmpty() {
        ClientOffer o = offer();
        o.getItems().add(tenge(o, "50000"));
        assertThat(calc.calculate(o, Map.of("RUB", new BigDecimal("5.48"))).totals().purchaseToday()).isNull();
    }

    @Test
    void todayPurchase_perUnitOneFraction_thenQuantity() {
        ClientOffer o = offer();
        o.getItems().add(rub(o, "0.25", "5.5", 3));       // 0,25 × 5,5 = 1,375 → 1,38 за ед.; × 3 = 4,14
        assertThat(calc.calculate(o, Map.of("RUB", new BigDecimal("5.5"))).totals().purchaseToday()).isEqualByComparingTo("4.14");
    }
}
