package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ComplectComponentMatcherTest {

    @Test
    void siliconeElectrode55x80_ranksAboveTherapeutic() {
        Set<String> lot = ComplectComponentMatcher.tokenize("Электрод электроды силиконовые 55*80 мм");
        double silicone = ComplectComponentMatcher.score(lot,
                "4.Электроды силиконовые электропроводящие, мм: - 25 х 30; - 55 х 80; - 100 х 120;");
        double therapeutic = ComplectComponentMatcher.score(lot,
                "2.Электроды токопроводящие терапевтические: - 40 х 50; - 90 х 140;");
        assertThat(silicone).isGreaterThan(therapeutic);
        assertThat(silicone).isGreaterThan(0.0);
    }

    @Test
    void tokenizeDropsNoiseKeepsDigitsAndWords() {
        Set<String> t = ComplectComponentMatcher.tokenize("Электрод силиконовый 55 мм для");
        assertThat(t).contains("55", "силиконовый").doesNotContain("мм", "для");
    }
}
