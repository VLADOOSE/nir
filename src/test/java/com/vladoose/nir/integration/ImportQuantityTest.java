package com.vladoose.nir.integration;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Количество и сумма лота с площадки (B3): не целое > 0 / не положительная сумма → пусто, а не отказ записи. */
class ImportQuantityTest {

    @Test void whole() {
        assertThat(ImportQuantity.wholePositiveOrNull(new BigDecimal("1.00"))).isEqualTo(1);
        assertThat(ImportQuantity.wholePositiveOrNull(new BigDecimal("487"))).isEqualTo(487);
        assertThat(ImportQuantity.wholePositiveOrNull(new BigDecimal("2.5"))).isNull();
        assertThat(ImportQuantity.wholePositiveOrNull(new BigDecimal("0.5"))).isNull();
        assertThat(ImportQuantity.wholePositiveOrNull(BigDecimal.ZERO)).isNull();
        assertThat(ImportQuantity.wholePositiveOrNull(new BigDecimal("-3"))).isNull();
        assertThat(ImportQuantity.wholePositiveOrNull(null)).isNull();
        assertThat(ImportQuantity.wholePositiveOrNull(new BigDecimal("3000000000"))).isNull();
        assertThat(ImportQuantity.wholePositiveOrNull(new BigDecimal("1E+3"))).isEqualTo(1000);
    }

    @Test void parse() {
        assertThat(ImportQuantity.parse("1 000")).isEqualByComparingTo("1000");
        assertThat(ImportQuantity.parse("1 000.00")).isEqualByComparingTo("1000");
        assertThat(ImportQuantity.parse("1 000.00")).isEqualByComparingTo("1000");
        assertThat(ImportQuantity.parse("2,5")).isEqualByComparingTo("2.5");
        assertThat(ImportQuantity.parse("шт")).isNull();
        assertThat(ImportQuantity.parse("")).isNull();
        assertThat(ImportQuantity.parse(null)).isNull();
    }

    @Test void note() {
        assertThat(ImportQuantity.note(" 2,5 ")).isEqualTo("Количество на площадке: 2,5");
    }

    @Test void money() {
        assertThat(ImportQuantity.positiveMoneyOrNull(null)).isNull();
        assertThat(ImportQuantity.positiveMoneyOrNull(BigDecimal.ZERO)).isNull();
        assertThat(ImportQuantity.positiveMoneyOrNull(new BigDecimal("-1"))).isNull();
        assertThat(ImportQuantity.positiveMoneyOrNull(new BigDecimal("12345678901234"))).isNull();
        assertThat(ImportQuantity.positiveMoneyOrNull(new BigDecimal("1234567890123"))).isEqualByComparingTo("1234567890123");
        assertThat(ImportQuantity.positiveMoneyOrNull(new BigDecimal("180.50"))).isEqualByComparingTo("180.50");
    }

    @Test void withNote() {
        assertThat(ImportQuantity.withNote(null, "Описание")).isEqualTo("Описание");
        assertThat(ImportQuantity.withNote(null, null)).isNull();
        assertThat(ImportQuantity.withNote("Количество на площадке: 2.5", null)).isEqualTo("Количество на площадке: 2.5");
        assertThat(ImportQuantity.withNote("Количество на площадке: 2.5", "  ")).isEqualTo("Количество на площадке: 2.5");
        assertThat(ImportQuantity.withNote("Количество на площадке: 2.5", "Описание"))
                .isEqualTo("Количество на площадке: 2.5\nОписание");
    }

    @Test void withNote_idempotent() {
        String once = ImportQuantity.withNote("Количество на площадке: 2.5", "Описание");
        assertThat(ImportQuantity.withNote("Количество на площадке: 2.5", once)).isEqualTo(once);
    }
}
