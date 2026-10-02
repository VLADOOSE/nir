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
 * Единственное место формул КП (спека client-kp-constructor §5). Наценка — «с учётом НДС» (решение оператора
 * 2026-10-02): себестоимость = закупка без входного НДС, если строку продаём с НДС; если без НДС — входной НДС
 * к зачёту не идёт и остаётся в себестоимости. Цена = себестоимость × (1 + наценка) × (1 + наш НДС) → округление.
 * Ручная цена фиксируется, наценка выводится обратно. НДС выделяется из суммы («в т.ч.»), суммы — HALF_UP до 0,01.
 */
@Component
public class ClientOfferCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final int WORK_SCALE = 10;

    public OfferCalculation calculate(ClientOffer offer) {
        List<ItemCalc> items = new ArrayList<>(offer.getItems().size());
        Map<BigDecimal, BigDecimal> vatByRate = new TreeMap<>();
        BigDecimal sum = zero(), vatTotal = zero(), purchase = zero(), cost = zero(), revenueNet = zero(), profit = zero();
        BigDecimal costWithProfit = zero(), netWithProfit = zero();
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
            else purchase = purchase.add(item.getPurchasePrice().multiply(qty(item)));

            ItemCalc c = calculateItem(offer, item);
            items.add(c);
            if (c.sum() != null) {
                sum = sum.add(c.sum());
                vatTotal = vatTotal.add(c.vatSum());
                revenueNet = revenueNet.add(c.sumNet());
                if (c.effectiveVatRate() != null) vatByRate.merge(c.effectiveVatRate(), c.vatSum(), BigDecimal::add);
            }
            if (c.costTotal() != null) cost = cost.add(c.costTotal());
            if (c.profit() != null) {
                profit = profit.add(c.profit());
                costWithProfit = costWithProfit.add(c.costTotal());
                netWithProfit = netWithProfit.add(c.sumNet());
            }
        }

        List<VatLine> vat = new ArrayList<>();
        vatByRate.forEach((rate, amount) -> vat.add(new VatLine(rate, amount)));
        BigDecimal markupAvg = costWithProfit.signum() > 0
                ? netWithProfit.subtract(costWithProfit).multiply(HUNDRED).divide(costWithProfit, 2, RoundingMode.HALF_UP)
                : null;
        OfferTotals totals = new OfferTotals(sum, vat, vatTotal, purchase.setScale(2, RoundingMode.HALF_UP), cost,
                revenueNet, profit, markupAvg, itemCount, noPurchase, unconfirmed);
        return new OfferCalculation(items, totals);
    }

    ItemCalc calculateItem(ClientOffer offer, ClientOfferItem item) {
        BigDecimal q = qty(item);
        BigDecimal v = offer.isVatEnabled() ? item.getVatRate() : null;
        boolean taxable = v != null;
        BigDecimal p = item.isPurchaseVatSame() ? v : item.getPurchaseVatRate();

        BigDecimal costRaw = null;
        if (item.getPurchasePrice() != null) {
            costRaw = taxable && p != null
                    ? item.getPurchasePrice().divide(factor(p), WORK_SCALE, RoundingMode.HALF_UP)
                    : item.getPurchasePrice();
        }

        BigDecimal price;
        BigDecimal markup;
        if (item.getPriceOverride() != null) {
            price = item.getPriceOverride().setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = taxable ? price.divide(factor(v), WORK_SCALE, RoundingMode.HALF_UP) : price;
            markup = costRaw != null && costRaw.signum() > 0
                    ? net.divide(costRaw, WORK_SCALE, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                            .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP)
                    : null;
        } else if (costRaw != null) {
            BigDecimal m = item.getMarkupPct() != null ? item.getMarkupPct() : offer.getDefaultMarkupPct();
            BigDecimal net = costRaw.multiply(factor(m));
            price = round(taxable ? net.multiply(factor(v)) : net, offer.getRounding());
            markup = m.setScale(2, RoundingMode.HALF_UP);
        } else {
            return new ItemCalc(null, null, item.getMarkupPct(), null, null, null, null, null, null, v);
        }

        BigDecimal sum = price.multiply(q).setScale(2, RoundingMode.HALF_UP);
        BigDecimal vatSum = taxable
                ? sum.multiply(v).divide(HUNDRED.add(v), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
        BigDecimal sumNet = sum.subtract(vatSum);
        BigDecimal priceNet = taxable ? price.divide(factor(v), 2, RoundingMode.HALF_UP) : price;
        BigDecimal costTotal = costRaw == null ? null : costRaw.multiply(q).setScale(2, RoundingMode.HALF_UP);
        BigDecimal profit = costTotal == null ? null : sumNet.subtract(costTotal);
        BigDecimal cost = costRaw == null ? null : costRaw.setScale(2, RoundingMode.HALF_UP);
        return new ItemCalc(cost, costTotal, markup, priceNet, price, sum, vatSum, sumNet, profit, v);
    }

    static BigDecimal round(BigDecimal value, OfferRounding rounding) {
        return switch (rounding == null ? OfferRounding.NONE : rounding) {
            case UNIT -> value.setScale(0, RoundingMode.HALF_UP).setScale(2);
            case TEN -> value.divide(TEN, 0, RoundingMode.HALF_UP).multiply(TEN).setScale(2);
            case HUNDRED -> value.divide(HUNDRED, 0, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(2);
            case NONE -> value.setScale(2, RoundingMode.HALF_UP);
        };
    }

    private static BigDecimal factor(BigDecimal percent) {
        return BigDecimal.ONE.add(percent.divide(HUNDRED, WORK_SCALE, RoundingMode.HALF_UP));
    }

    private static BigDecimal qty(ClientOfferItem item) {
        return item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity();
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
