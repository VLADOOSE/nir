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

    /** B1: goszakup присылает сумму лота и количество — «Макс. цена» лота должна быть ценой за единицу. */
    @Test void unitPrice() {
        assertThat(ImportQuantity.unitPriceOrNull(new BigDecimal("54000"), new BigDecimal("300"))).isEqualByComparingTo("180.00");
        assertThat(ImportQuantity.unitPriceOrNull(new BigDecimal("1000"), new BigDecimal("3"))).isEqualByComparingTo("333.33");
        assertThat(ImportQuantity.unitPriceOrNull(new BigDecimal("2"), new BigDecimal("3"))).isEqualByComparingTo("0.67");
        // дробное количество (кг, литры) — цена за единицу измерения
        assertThat(ImportQuantity.unitPriceOrNull(new BigDecimal("5000"), new BigDecimal("0.5"))).isEqualByComparingTo("10000.00");
        // после округления 0,00 — пусто, иначе CHECK max_cost > 0 уронит запись тендера
        assertThat(ImportQuantity.unitPriceOrNull(new BigDecimal("0.01"), new BigDecimal("3"))).isNull();
        assertThat(ImportQuantity.unitPriceOrNull(new BigDecimal("5000"), null)).isNull();
        assertThat(ImportQuantity.unitPriceOrNull(new BigDecimal("5000"), BigDecimal.ZERO)).isNull();
        assertThat(ImportQuantity.unitPriceOrNull(new BigDecimal("5000"), new BigDecimal("-2"))).isNull();
        assertThat(ImportQuantity.unitPriceOrNull(BigDecimal.ZERO, new BigDecimal("3"))).isNull();
        assertThat(ImportQuantity.unitPriceOrNull(null, new BigDecimal("3"))).isNull();
        assertThat(ImportQuantity.unitPriceOrNull(new BigDecimal("12345678901234"), BigDecimal.ONE)).isNull();
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
