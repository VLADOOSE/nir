package com.vladoose.nir.service.offer;

import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.entity.ClientOfferItem;
import com.vladoose.nir.entity.ClientOfferItemKind;
import com.vladoose.nir.entity.OfferRegistrationStatus;
import com.vladoose.nir.entity.OfferRounding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Единственное место формул КП (спека client-kp-constructor §5). Решение оператора 2026-10-05: наценка закладывает наш
 * НДС, НДС поставщика не учитывается (вычет — задача бухгалтерии). Себестоимость = закупка (как в счёте поставщика), цена
 * клиенту = закупка × (100 + наценка) / 100 → округление — с нашим НДС уже внутри. Пометку «НДС в цене закупки» строки
 * (purchaseVatSame / purchaseVatRate) расчёт не читает — поля остались для совместимости. Ручная цена фиксируется,
 * наценка выводится обратно от закупки. НДС выделяется из суммы («в т.ч.»), суммы — HALF_UP до 0,01.
 * Всё, что округляется (цена, суммы, НДС, наценка ручной цены), считается ОДНОЙ точной дробью — одно деление, одно
 * округление (§5.1–5.2). Не делить «до N знаков» и умножать обратно: шум ±1e-10 на точной «половинке» уводил HALF_UP
 * вниз (1 080 + 25% «до 100» давало 1 300 вместо 1 400).
 */
@Component
public class ClientOfferCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TEN = BigDecimal.TEN;

    public OfferCalculation calculate(ClientOffer offer) {
        List<ItemCalc> items = new ArrayList<>(offer.getItems().size());
        Map<BigDecimal, BigDecimal> vatByRate = new TreeMap<>();
        BigDecimal sum = zero(), vatTotal = zero(), purchase = zero(), revenueNet = zero(), profit = zero();
        BigDecimal markedSum = zero(), markedPurchase = zero();
        int itemCount = 0, noPurchase = 0, unconfirmed = 0;

        for (ClientOfferItem item : offer.getItems()) {
            if (item.getKind() != ClientOfferItemKind.ITEM) {
                items.add(ItemCalc.NONE);
                continue;
            }
            itemCount++;
            OfferRegistrationStatus reg = item.getRegistrationStatus();
            if (reg == OfferRegistrationStatus.UNCHECKED || reg == OfferRegistrationStatus.SUGGESTED) unconfirmed++;
            if (item.getPurchasePrice() == null) noPurchase++;

            ItemCalc c = calculateItem(offer, item);
            items.add(c);
            if (c.sum() != null) {
                sum = sum.add(c.sum());
                vatTotal = vatTotal.add(c.vatSum());
                revenueNet = revenueNet.add(c.sumNet());
                if (c.effectiveVatRate() != null) vatByRate.merge(c.effectiveVatRate(), c.vatSum(), BigDecimal::add);
            }
            if (c.costTotal() != null) {
                // закупка строки уже до 0,01 — итог «Закупка» = Σ закупок строк, и прибыль = выручка без НДС − закупка
                purchase = purchase.add(c.costTotal());
                profit = profit.add(c.profit());
                // средняя наценка — только где закупка больше нуля: у закупки 0 наценки нет («—»)
                if (item.getPurchasePrice().signum() > 0) {
                    markedSum = markedSum.add(c.sum());
                    markedPurchase = markedPurchase.add(c.costTotal());
                }
            }
        }

        List<VatLine> vat = new ArrayList<>();
        vatByRate.forEach((rate, amount) -> vat.add(new VatLine(rate, amount)));
        // от закупки, по суммам с НДС: одна наценка 20 % у всех строк — средняя 20 %, при любых ставках НДС
        BigDecimal markupAvg = markedPurchase.signum() > 0
                ? markedSum.subtract(markedPurchase).multiply(HUNDRED).divide(markedPurchase, 2, RoundingMode.HALF_UP)
                : null;
        // cost (себестоимость) = purchase: НДС поставщика не вычитается; поле оставлено для совместимости API
        OfferTotals totals = new OfferTotals(sum, vat, vatTotal, purchase, purchase, revenueNet, profit, markupAvg,
                itemCount, noPurchase, unconfirmed);
        return new OfferCalculation(items, totals);
    }

    ItemCalc calculateItem(ClientOffer offer, ClientOfferItem item) {
        BigDecimal q = qty(item);
        BigDecimal v = offer.isVatEnabled() ? item.getVatRate() : null;
        boolean taxable = v != null;
        BigDecimal p = item.getPurchasePrice();   // за единицу, как в счёте поставщика: его НДС не вычитается

        BigDecimal price;
        BigDecimal markup;
        if (item.getPriceOverride() != null) {
            price = item.getPriceOverride().setScale(2, RoundingMode.HALF_UP);
            markup = p != null && p.signum() > 0 ? manualMarkup(price, p) : null;
        } else if (p != null) {
            BigDecimal m = item.getMarkupPct() != null ? item.getMarkupPct() : offer.getDefaultMarkupPct();
            // цена = закупка × (100 + m) / 100 — наш НДС уже внутри (наценка его закладывает), сверху не начисляется
            price = round(p.multiply(HUNDRED.add(m)), HUNDRED, offer.getRounding());
            markup = m.setScale(2, RoundingMode.HALF_UP);
        } else {
            return new ItemCalc(null, null, item.getMarkupPct(), null, null, null, null, null, null, v);
        }

        BigDecimal sum = price.multiply(q).setScale(2, RoundingMode.HALF_UP);
        BigDecimal vatSum = taxable
                ? sum.multiply(v).divide(HUNDRED.add(v), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
        BigDecimal sumNet = sum.subtract(vatSum);
        BigDecimal priceNet = taxable ? price.multiply(HUNDRED).divide(HUNDRED.add(v), 2, RoundingMode.HALF_UP) : price;
        BigDecimal cost = p == null ? null : p.setScale(2, RoundingMode.HALF_UP);
        BigDecimal costTotal = p == null ? null : p.multiply(q).setScale(2, RoundingMode.HALF_UP);
        // Прибыль — остаток уже округлённых сумм: то, что остаётся после уплаты нашего НДС; прибыль + закупка строки =
        // сумма без НДС.
        BigDecimal profit = costTotal == null ? null : sumNet.subtract(costTotal);
        return new ItemCalc(cost, costTotal, markup, priceNet, price, sum, vatSum, sumNet, profit, v);
    }

    /** Наценка ручной цены одной дробью: m = (цена / закупка − 1) × 100 = (цена − закупка) × 100 / закупка. */
    private static BigDecimal manualMarkup(BigDecimal price, BigDecimal purchase) {
        return price.subtract(purchase).multiply(HUNDRED).divide(purchase, 2, RoundingMode.HALF_UP);
    }

    /** Цена num / den по правилу КП (§5.2): одно деление точной дроби с HALF_UP; результат — с двумя знаками. */
    static BigDecimal round(BigDecimal num, BigDecimal den, OfferRounding rounding) {
        return switch (rounding == null ? OfferRounding.NONE : rounding) {
            case UNIT -> num.divide(den, 0, RoundingMode.HALF_UP).setScale(2);
            case TEN -> num.divide(den.multiply(TEN), 0, RoundingMode.HALF_UP).multiply(TEN).setScale(2);
            case HUNDRED -> num.divide(den.multiply(HUNDRED), 0, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(2);
            case NONE -> num.divide(den, 2, RoundingMode.HALF_UP);
        };
    }

    private static BigDecimal qty(ClientOfferItem item) {
        return item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity();
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
