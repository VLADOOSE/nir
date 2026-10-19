package com.vladoose.nir.service.execution;

import com.vladoose.nir.entity.ContractKind;
import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Проверка ввода исполнения (спека 2026-10-19-deal-execution §7). */
class ExecutionInputTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Test
    void amounts() {
        assertThat(ExecutionInput.amount("1 535 227,50", "Сумма")).isEqualByComparingTo("1535227.50");
        assertThat(ExecutionInput.amount("1 535 228", "Сумма")).isEqualByComparingTo("1535228");
        assertThat(ExecutionInput.amount("9999999999999.99", "Сумма")).isEqualByComparingTo("9999999999999.99");
        assertThat(ExecutionInput.amount("1535227.500", "Сумма")).isEqualByComparingTo("1535227.50");
        assertThatThrownBy(() -> ExecutionInput.amount("0", "Сумма")).hasMessage("Сумма: сумма должна быть больше нуля");
        assertThatThrownBy(() -> ExecutionInput.amount("-5", "Сумма")).hasMessage("Сумма: сумма должна быть больше нуля");
        assertThatThrownBy(() -> ExecutionInput.amount("1.005", "Сумма")).hasMessage("Сумма: не больше 2 знаков после запятой");
        assertThatThrownBy(() -> ExecutionInput.amount("10000000000000", "Сумма"))
                .hasMessage("Сумма: слишком большая сумма — не больше 13 цифр до запятой");
        assertThatThrownBy(() -> ExecutionInput.amount("1e200000000", "Сумма"))
                .hasMessage("Сумма: слишком большая сумма — не больше 13 цифр до запятой");
        assertThatThrownBy(() -> ExecutionInput.amount("abc", "Сумма")).hasMessage("Сумма: нужно число, например 1 535 228");
        assertThatThrownBy(() -> ExecutionInput.amount((java.math.BigDecimal) null, "Сумма")).hasMessage("Сумма: укажите сумму");
    }

    @Test
    void dates_notAfterMarketToday() {
        assertThat(ExecutionInput.date("2026-10-20", "Дата", TODAY)).isEqualTo(TODAY);
        assertThatThrownBy(() -> ExecutionInput.date("2026-10-21", "Дата", TODAY))
                .hasMessage("Дата: дата ещё не наступила — отмечаются только факты");
        assertThatThrownBy(() -> ExecutionInput.date("20.10.2026", "Дата", TODAY)).hasMessage("Дата: дата в виде ГГГГ-ММ-ДД");
        assertThatThrownBy(() -> ExecutionInput.date((LocalDate) null, "Дата", TODAY)).hasMessage("Дата: укажите дату");
    }

    @Test
    void todayIsMarketDate_notUtc() {
        Clock lateUtc = Clock.fixed(Instant.parse("2026-10-19T19:30:00Z"), ZoneOffset.UTC);   // 00:30 20.10 по Уральску
        assertThat(ExecutionInput.today(lateUtc, Market.KZ.zone())).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(ExecutionInput.today(lateUtc, Market.RF.zone())).isEqualTo(LocalDate.of(2026, 10, 19));   // Самара — 23:30
        assertThat(ExecutionInput.date("2026-10-20", "Дата", ExecutionInput.today(lateUtc, Market.KZ.zone()))).isEqualTo(TODAY);
    }

    @Test
    void pctKindCurrencyBoolText() {
        assertThat(ExecutionInput.pct("50")).isEqualByComparingTo("50");
        assertThat(ExecutionInput.pct("12,5 %")).isEqualByComparingTo("12.5");
        assertThat(ExecutionInput.pct("0")).isEqualByComparingTo("0");
        assertThatThrownBy(() -> ExecutionInput.pct("101")).hasMessage("Предоплата: число от 0 до 100");
        assertThatThrownBy(() -> ExecutionInput.pct("-1")).hasMessage("Предоплата: число от 0 до 100");
        assertThatThrownBy(() -> ExecutionInput.pct("половина")).hasMessage("Предоплата: число от 0 до 100");
        assertThat(ExecutionInput.kind("INVOICE")).isEqualTo(ContractKind.INVOICE);
        assertThatThrownBy(() -> ExecutionInput.kind("ACT")).hasMessage("Вид — договор, счёт или без договора");
        assertThat(ExecutionInput.currency("rub")).isEqualTo("RUB");
        assertThatThrownBy(() -> ExecutionInput.currency("GBP")).hasMessage("Валюта — тг, руб., $, € или юань");
        assertThat(ExecutionInput.bool("true")).isTrue();
        assertThat(ExecutionInput.bool(null)).isFalse();
        assertThatThrownBy(() -> ExecutionInput.bool("yes")).hasMessage("Нужно true или false");
        assertThat(ExecutionInput.text("  ", 10, "Заметка")).isNull();
        assertThat(ExecutionInput.text(" 42 ", 10, "Заметка")).isEqualTo("42");
        assertThatThrownBy(() -> ExecutionInput.text("x".repeat(11), 10, "Заметка")).hasMessage("Заметка: не больше 10 символов");
    }
}
