package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Что из «нашей продукции» уходит в Telegram (решение оператора 2026-10-20): режим и порог суммы наших лотов. */
class ProductNotifyPolicyTest {

    @Test
    void emptySettings_onlyStarFromOneMillion() {
        ProductNotifyPolicy p = ProductNotifyPolicy.of("", null);
        assertThat(p.mode()).isEqualTo(ProductNotifyPolicy.Mode.STAR);
        assertThat(p.minSum()).isEqualByComparingTo("1000000");
    }

    @Test
    void parsesModeCaseInsensitiveAndSumWithSpaces() {
        ProductNotifyPolicy p = ProductNotifyPolicy.of(" all ", "500 000");
        assertThat(p.mode()).isEqualTo(ProductNotifyPolicy.Mode.ALL);
        assertThat(p.minSum()).isEqualByComparingTo("500000");
        assertThat(ProductNotifyPolicy.of("Off", "0").mode()).isEqualTo(ProductNotifyPolicy.Mode.OFF);
    }

    /** Опечатка в .env не должна ни ронять старт, ни включать поток сообщений: остаётся тихое умолчание. */
    @Test
    void garbage_fallsBackToQuietDefault() {
        ProductNotifyPolicy p = ProductNotifyPolicy.of("всё", "миллион");
        assertThat(p.mode()).isEqualTo(ProductNotifyPolicy.Mode.STAR);
        assertThat(p.minSum()).isEqualByComparingTo("1000000");
        assertThat(ProductNotifyPolicy.of("all", "-5").minSum()).isEqualByComparingTo("1000000");
    }

    @Test
    void levels_byMode() {
        ProductNotifyPolicy star = ProductNotifyPolicy.of("star", "0");
        assertThat(star.levelPasses(ProductMatchLevel.TYPE)).isFalse();
        assertThat(star.levelPasses(ProductMatchLevel.NAMED)).isTrue();
        assertThat(star.levelPasses(ProductMatchLevel.COPIED)).isTrue();
        assertThat(ProductNotifyPolicy.of("all", "0").levelPasses(ProductMatchLevel.TYPE)).isTrue();
        assertThat(ProductNotifyPolicy.of("off", "0").levelPasses(ProductMatchLevel.COPIED)).isFalse();
    }

    /** «От 1 млн»: ровно миллион проходит, копейкой меньше — нет; сумма неизвестна — не проходит (не доказано). */
    @Test
    void sum_fromThresholdInclusive_unknownFails() {
        ProductNotifyPolicy p = ProductNotifyPolicy.of("star", "");
        assertThat(p.sumPasses(new BigDecimal("1000000.00"))).isTrue();
        assertThat(p.sumPasses(new BigDecimal("999999.99"))).isFalse();
        assertThat(p.sumPasses(null)).isFalse();
        assertThat(ProductNotifyPolicy.of("star", "0").sumPasses(null)).as("порог 0 — без порога").isTrue();
    }
}
