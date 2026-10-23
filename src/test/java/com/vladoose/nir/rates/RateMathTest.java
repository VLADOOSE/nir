package com.vladoose.nir.rates;

import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.rates.RateMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateMathTest {

    @Test
    void convert_oneFraction_halfUp() {
        assertThat(RateMath.convert(new BigDecimal("277025"), new BigDecimal("6"))).isEqualByComparingTo("1662150.00");
        assertThat(RateMath.convert(new BigDecimal("150000"), new BigDecimal("5.48"))).isEqualByComparingTo("822000.00");
        // 0,125 × 1 = 0,125 → 0,13 (HALF_UP), а не 0,12
        assertThat(RateMath.convert(new BigDecimal("0.125"), BigDecimal.ONE)).isEqualByComparingTo("0.13");
        assertThat(RateMath.convert(new BigDecimal("9999999999999"), new BigDecimal("10"))).isNull();   // больше 13 цифр
        assertThat(RateMath.convert(null, BigDecimal.ONE)).isNull();
    }

    /** Оракул на целых (спека §12, как у расчёта КП): тиыны × миллионные доли курса — до тиына HALF_UP. */
    @Test
    void convert_matchesIntegerOracle() {
        java.util.Random rnd = new java.util.Random(20261023);
        for (int i = 0; i < 200_000; i++) {
            long cents = rnd.nextLong(0, 100_000_000_000L);           // до 1 млрд в валюте
            long micro = rnd.nextLong(1, 100_000_000_000L);           // курс до 100 000 с 6 знаками
            java.math.BigInteger exact = java.math.BigInteger.valueOf(cents).multiply(java.math.BigInteger.valueOf(micro))
                    .add(java.math.BigInteger.valueOf(500_000)).divide(java.math.BigInteger.valueOf(1_000_000));
            BigDecimal got = RateMath.convert(BigDecimal.valueOf(cents, 2), BigDecimal.valueOf(micro, 6));
            if (got == null) continue;                                 // больше 13 цифр — не пересчитывается
            assertThat(got.setScale(2).unscaledValue()).isEqualTo(exact);
        }
    }

    @Test
    void check_limits() {
        assertThat(RateMath.check(new BigDecimal("5.485555"))).isEqualByComparingTo("5.485555");
        assertThat(RateMath.check(null)).isNull();
        assertThatThrownBy(() -> RateMath.check(BigDecimal.ZERO)).isInstanceOf(BadRequestException.class).hasMessage("Курс — число больше нуля");
        assertThatThrownBy(() -> RateMath.check(new BigDecimal("100000.1"))).hasMessage("Курс слишком большой — не больше 100 000");
        assertThatThrownBy(() -> RateMath.check(new BigDecimal("1.1234567"))).hasMessage("Курс — не больше 6 знаков после запятой");
    }

    @Test
    void textAndMargin() {
        assertThat(RateMath.text(new BigDecimal("6.000000"))).isEqualTo("6");
        assertThat(RateMath.text(new BigDecimal("5.480000"))).isEqualTo("5,48");
        assertThat(RateMath.marginPct(new BigDecimal("6"), new BigDecimal("5.48"))).isEqualByComparingTo("9.5");
        assertThat(RateMath.marginPct(new BigDecimal("6"), null)).isNull();
    }
}
