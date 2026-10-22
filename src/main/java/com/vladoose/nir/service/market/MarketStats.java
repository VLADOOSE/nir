package com.vladoose.nir.service.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Цена рынка по строкам договоров (спека 2026-10-22-tender-results-market-prices §9): число, медиана, середина рынка (25-й…75-й
 * процентиль), минимум и максимум; медиана бюджета; отклонение бюджета лота от медианы, %. Процентили — линейной интерполяцией;
 * одна строка — она сама (Review Focus 5).
 */
public record MarketStats(int count, BigDecimal median, BigDecimal p25, BigDecimal p75, BigDecimal min, BigDecimal max,
                          BigDecimal budgetMedian, BigDecimal lotBudget, Integer budgetDiffPct) {

    public static MarketStats of(List<BigDecimal> prices, List<BigDecimal> budgets, BigDecimal lotBudget) {
        List<BigDecimal> p = prices.stream().filter(Objects::nonNull).filter(x -> x.signum() > 0).sorted().toList();
        List<BigDecimal> b = budgets.stream().filter(Objects::nonNull).filter(x -> x.signum() > 0).sorted().toList();
        BigDecimal budgetMedian = b.isEmpty() ? null : percentile(b, 50);
        if (p.isEmpty()) return new MarketStats(0, null, null, null, null, null, budgetMedian, lotBudget, null);
        BigDecimal median = percentile(p, 50);
        Integer diff = lotBudget == null || median.signum() == 0 ? null
                : lotBudget.subtract(median).multiply(BigDecimal.valueOf(100)).divide(median, 0, RoundingMode.HALF_UP).intValue();
        return new MarketStats(p.size(), median, percentile(p, 25), percentile(p, 75), p.get(0), p.get(p.size() - 1), budgetMedian,
                lotBudget, diff);
    }

    public static BigDecimal percentile(List<BigDecimal> sorted, int pct) {
        if (sorted.size() == 1) return sorted.get(0).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pos = BigDecimal.valueOf(sorted.size() - 1L).multiply(BigDecimal.valueOf(pct)).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        int lo = pos.intValue();
        BigDecimal frac = pos.subtract(BigDecimal.valueOf(lo));
        BigDecimal a = sorted.get(lo);
        BigDecimal c = sorted.get(Math.min(lo + 1, sorted.size() - 1));
        return a.add(c.subtract(a).multiply(frac)).setScale(2, RoundingMode.HALF_UP);
    }
}
