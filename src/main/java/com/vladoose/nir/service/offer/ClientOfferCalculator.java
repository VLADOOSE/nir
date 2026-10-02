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
 * Всё, что округляется (цена, себестоимость строки, наценка ручной цены), считается ОДНОЙ точной дробью — одно деление,
 * одно округление (§5.1–5.2). Не делить себестоимость «до N знаков» и умножать обратно: шум ±1e-10 на точной
 * «половинке» уводил HALF_UP вниз (1 080 + 25% «до 100» давало 1 300 вместо 1 400).
 */
@Component
public class ClientOfferCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

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

        // Себестоимость за единицу — точной дробью costNum / costDen (без деления): входной НДС вычитается, только если
        // строку продаём с НДС и ставка закупки известна; иначе он остаётся в себестоимости.
        BigDecimal costNum = null;
        BigDecimal costDen = null;
        if (item.getPurchasePrice() != null) {
            boolean excludeInputVat = taxable && p != null;
            costNum = excludeInputVat ? item.getPurchasePrice().multiply(HUNDRED) : item.getPurchasePrice();
            costDen = excludeInputVat ? HUNDRED.add(p) : BigDecimal.ONE;
        }

        BigDecimal price;
        BigDecimal markup;
        if (item.getPriceOverride() != null) {
            price = item.getPriceOverride().setScale(2, RoundingMode.HALF_UP);
            markup = costNum != null && costNum.signum() > 0 ? manualMarkup(price, v, costNum, costDen) : null;
        } else if (costNum != null) {
            BigDecimal m = item.getMarkupPct() != null ? item.getMarkupPct() : offer.getDefaultMarkupPct();
            // цена = себестоимость × (100 + m) / 100 × (100 + v) / 100; без НДС — без последнего множителя
            BigDecimal num = costNum.multiply(HUNDRED.add(m)).multiply(taxable ? HUNDRED.add(v) : HUNDRED);
            price = round(num, costDen.multiply(TEN_THOUSAND), offer.getRounding());
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
        BigDecimal cost = costNum == null ? null : costNum.divide(costDen, 2, RoundingMode.HALF_UP);
        BigDecimal costTotal = costNum == null ? null : costNum.multiply(q).divide(costDen, 2, RoundingMode.HALF_UP);
        // Прибыль — остаток уже округлённых сумм: в каждой строке прибыль + себестоимость строки = сумма без НДС.
        BigDecimal profit = costTotal == null ? null : sumNet.subtract(costTotal);
        return new ItemCalc(cost, costTotal, markup, priceNet, price, sum, vatSum, sumNet, profit, v);
    }

    /**
     * Наценка ручной цены одной дробью: m = net / себестоимость × 100 − 100, где net = цена × 100 / (100 + v)
     * (без НДС — сама цена). Когда входной НДС вычитается (строка с НДС, ставка закупки p известна), это
     * цена × (100 + p) × 100 / ((100 + v) × закупка) − 100.
     */
    private static BigDecimal manualMarkup(BigDecimal price, BigDecimal v, BigDecimal costNum, BigDecimal costDen) {
        BigDecimal netNum = v != null ? price.multiply(HUNDRED) : price;
        BigDecimal netDen = v != null ? HUNDRED.add(v) : BigDecimal.ONE;
        BigDecimal den = netDen.multiply(costNum);
        return netNum.multiply(costDen).multiply(HUNDRED).subtract(den.multiply(HUNDRED))
                .divide(den, 2, RoundingMode.HALF_UP);
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
