package com.vladoose.nir.market;

import com.vladoose.nir.service.market.MarketStats;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Расчёт цены рынка (спека 2026-10-22-tender-results-market-prices §9; Review Focus 5). */
class MarketStatsTest {

    private static List<BigDecimal> list(String... v) {
        return java.util.Arrays.stream(v).map(BigDecimal::new).toList();
    }

    @Test
    void oneRow_isEverything() {
        MarketStats s = MarketStats.of(list("1340000"), list(), null);
        assertThat(s.count()).isEqualTo(1);
        assertThat(s.median()).isEqualByComparingTo("1340000");
        assertThat(s.p25()).isEqualByComparingTo("1340000");
        assertThat(s.p75()).isEqualByComparingTo("1340000");
        assertThat(s.budgetDiffPct()).isNull();
    }

    @Test
    void medianPercentilesBudget() {
        MarketStats s = MarketStats.of(list("400", "100", "300", "200"), list("500", "300", "100"), new BigDecimal("265"));
        assertThat(s.median()).isEqualByComparingTo("250");
        assertThat(s.p25()).isEqualByComparingTo("175");
        assertThat(s.p75()).isEqualByComparingTo("325");
        assertThat(s.min()).isEqualByComparingTo("100");
        assertThat(s.max()).isEqualByComparingTo("400");
        assertThat(s.budgetMedian()).isEqualByComparingTo("300");
        assertThat(s.budgetDiffPct()).isEqualTo(6);
    }

    @Test
    void empty() {
        MarketStats s = MarketStats.of(List.of(), List.of(), new BigDecimal("1"));
        assertThat(s.count()).isZero();
        assertThat(s.median()).isNull();
    }
}
