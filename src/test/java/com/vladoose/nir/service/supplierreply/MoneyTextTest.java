package com.vladoose.nir.service.supplierreply;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTextTest {

    @Test
    void amounts() {
        assertThat(MoneyText.amount("1 360 000 тенге")).isEqualByComparingTo("1360000");
        assertThat(MoneyText.amount("7 200,00")).isEqualByComparingTo("7200");
        assertThat(MoneyText.amount("4700.5")).isEqualByComparingTo("4700.50");
        assertThat(MoneyText.amount("1.360.000")).isEqualByComparingTo("1360000");
        assertThat(MoneyText.amount("1,360,000.50")).isEqualByComparingTo("1360000.50");
        assertThat(MoneyText.amount("48 500,50 ₸")).isEqualByComparingTo("48500.50");
        assertThat(MoneyText.amount("1,5")).isEqualByComparingTo("1.5");
        assertThat(MoneyText.amount("14125")).isEqualByComparingTo("14125");
        assertThat(MoneyText.amount("нет")).isNull();
        assertThat(MoneyText.amount("Без НДС")).isNull();
        assertThat(MoneyText.amount("-5")).isNull();
        assertThat(MoneyText.amount("99999999999999999")).isNull();
        assertThat(MoneyText.amount(null)).isNull();
    }

    @Test
    void quantities() {
        assertThat(MoneyText.quantity("1 уп")).isEqualTo(new MoneyText.Qty(new BigDecimal("1"), "уп"));
        assertThat(MoneyText.quantity("2 шт.")).isEqualTo(new MoneyText.Qty(new BigDecimal("2"), "шт"));
        assertThat(MoneyText.quantity("10")).isEqualTo(new MoneyText.Qty(new BigDecimal("10"), null));
        assertThat(MoneyText.quantity("2,5 кг")).isEqualTo(new MoneyText.Qty(new BigDecimal("2.5"), "кг"));
        assertThat(MoneyText.quantity("по запросу")).isNull();
    }

    /** Word и выгрузки 1С ставят в числах неразрывные пробелы: «1 382 400» должно читаться так же, как с обычными. */
    @Test
    void nonBreakingSpacesInNumbers() {
        assertThat(MoneyText.amount("1 382 400 тг")).isEqualByComparingTo("1382400");
        assertThat(MoneyText.amount("48 500,50")).isEqualByComparingTo("48500.50");
        assertThat(MoneyText.amount("7 200")).isEqualByComparingTo("7200");
        assertThat(MoneyText.quantity("2 шт")).isEqualTo(new MoneyText.Qty(new BigDecimal("2"), "шт"));
    }

    @Test
    void currencies() {
        assertThat(MoneyText.currency("руб.")).isEqualTo("RUB");
        assertThat(MoneyText.currency("Цена, руб. за ед.")).isEqualTo("RUB");
        assertThat(MoneyText.currency("Цена, тг")).isEqualTo("KZT");
        assertThat(MoneyText.currency("₸")).isEqualTo("KZT");
        assertThat(MoneyText.currency("USD")).isEqualTo("USD");
        assertThat(MoneyText.currency("трубки соединительные")).isNull();
        assertThat(MoneyText.currency("Цена")).isNull();
        assertThat(MoneyText.label("RUB")).isEqualTo("руб.");
        assertThat(MoneyText.label("KZT")).isEqualTo("тг");
        // «у.е.» — условные единицы, не валюта рынка: такие цены не подставляются (ревью ветки)
        assertThat(MoneyText.currency("у.е.")).isEqualTo("UE");
        assertThat(MoneyText.currency("Цена, у. е.")).isEqualTo("UE");
        assertThat(MoneyText.label("UE")).isEqualTo("у.е.");
    }

    /**
     * Валюта в тексте вне таблицы (абзац под таблицей КП «Рикта», строка «Итого», бланк над шапкой) — только явным словом
     * или знаком: в описаниях изделий и реквизитах встречаются похожие слова («противорубцовый», «евростандарт»).
     */
    @Test
    void currenciesInText() {
        assertThat(MoneyText.textCurrencies("ВСЕГО К ОПЛАТЕ: 4 единицы товара на сумму 171 343,00 руб., НДС не облагается."))
                .containsExactly("RUB");
        assertThat(MoneyText.textCurrencies("Цены указаны в рублях РФ")).containsExactly("RUB");
        assertThat(MoneyText.textCurrencies("Итого: 238 500 руб")).containsExactly("RUB");
        assertThat(MoneyText.textCurrencies("Итого: 238 500 тенге")).containsExactly("KZT");
        assertThat(MoneyText.textCurrencies("Итого 238 500 тг.")).containsExactly("KZT");
        assertThat(MoneyText.textCurrencies("1 200 $, курс USD")).containsExactly("USD");
        assertThat(MoneyText.textCurrencies("цена в долларах США")).containsExactly("USD");
        assertThat(MoneyText.textCurrencies("цены в евро")).containsExactly("EUR");
        assertThat(MoneyText.textCurrencies("10 000 юаней")).containsExactly("CNY");
        assertThat(MoneyText.textCurrencies("в у.е. по курсу")).containsExactly("UE");
        assertThat(MoneyText.textCurrencies("₸ ₽ € ¥")).containsExactlyInAnyOrder("KZT", "RUB", "EUR", "CNY");
        assertThat(MoneyText.textCurrencies("KZT, RUB, EUR, CNY")).containsExactlyInAnyOrder("KZT", "RUB", "EUR", "CNY");
        assertThat(MoneyText.textCurrencies("Оплата в рублях по курсу на день оплаты, цены в тенге")).containsExactlyInAnyOrder("RUB", "KZT");
        assertThat(MoneyText.textCurrencies("Гель противорубцовый; рубцы; Рубцовск; рубеж; рубин")).isEmpty();
        assertThat(MoneyText.textCurrencies("вилка евростандарта, Neurosoft, Europe, rubber, Долли")).isEmpty();
        assertThat(MoneyText.textCurrencies("доставка от 1000 р., e-mail: sales@bk.ru")).isEmpty();
        assertThat(MoneyText.textCurrencies(null)).isEmpty();
        assertThat(MoneyText.label(MoneyText.MIXED)).isEqualTo("(валюта?)");
    }
}
