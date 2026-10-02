package com.vladoose.nir.clientoffer;

import com.vladoose.nir.util.DocFormat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DocFormatTest {

    private static final String NB = " ";

    @Test
    void formatsMoneyQuantityRateAndDate() {
        assertThat(DocFormat.money(new BigDecimal("9902248.23"))).isEqualTo("9" + NB + "902" + NB + "248,23");
        assertThat(DocFormat.money(BigDecimal.ZERO)).isEqualTo("0,00");
        assertThat(DocFormat.money(null)).isEmpty();
        assertThat(DocFormat.qty(new BigDecimal("1.000"))).isEqualTo("1");
        assertThat(DocFormat.qty(new BigDecimal("2.5"))).isEqualTo("2,5");
        assertThat(DocFormat.qty(new BigDecimal("1000"))).isEqualTo("1" + NB + "000");
        assertThat(DocFormat.rate(new BigDecimal("5.00"))).isEqualTo("5%");
        assertThat(DocFormat.rate(new BigDecimal("12.5"))).isEqualTo("12,5%");
        assertThat(DocFormat.rate(null)).isEqualTo("Без НДС");
        assertThat(DocFormat.date(LocalDate.of(2026, 9, 14))).isEqualTo("14.09.2026");
        assertThat(DocFormat.currencyShort("KZT")).isEqualTo("тг");
        assertThat(DocFormat.currencyShort("RUB")).isEqualTo("руб.");
    }
}
