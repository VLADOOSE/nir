package com.vladoose.nir.service.supplierreply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
     * Валюта в тексте вне таблицы (абзац под таблицей, строка «Итого», бланк над шапкой) — вплотную к числу: число, затем
     * валюта (между ними — пропись в скобках), или знак / код / «руб.», затем число. Все формы, которые пишут в КП.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Общая стоимость предложения — 18 450,00 руб., НДС не облагается.|RUB",
            "Итого: 238 500 руб|RUB",
            "Итого 238 500 рублей 00 копеек|RUB",
            "Итого 900 RUR|RUB",
            "Total 900 rubles|RUB",
            "Доставка — от 1000 р.|RUB",
            "Итого к оплате: руб. 18 450|RUB",
            "Итого: 238 500 тенге|KZT",
            "Итого 238 500 тг.|KZT",
            "Итого 238 500 теңге|KZT",
            "Итого 238 500 тнг|KZT",
            "Итого 238500KZT|KZT",
            "Итого 1 250 000,00 (Один миллион двести пятьдесят тысяч) тенге|KZT",
            "Итого 1 250 000 ,00 (Один миллион двести пятьдесят тысяч )тенге|KZT",
            "Итого $1 200|USD",
            "Итого 1 200 $|USD",
            "Итого USD 1 200|USD",
            "Итого 1 200 долл. США|USD",
            "Итого 1 200 дол. США|USD",
            "Итого 1 200 долларов США|USD",
            "Итого 900 Euro|EUR",
            "Итого 900 €|EUR",
            "Итого 10 000 юаней|CNY",
            "Итого 10 000 RMB|CNY",
            "Итого 10 000 у.е.|UE",
            "Итого 1 200 $ — курс USD на день оплаты|USD",
    })
    void currencyNextToNumber(String text, String code) {
        assertThat(MoneyText.textCurrencies(text)).as(text).containsExactly(code);
    }

    /** Явная фраза о валюте — и без числа рядом: «цены в рублях», «Валюта предложения: тенге». */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Цены указаны в рублях РФ|RUB",
            "Цены указаны в руб. с НДС|RUB",
            "Цены в тенге с НДС|KZT",
            "цена в долларах (США)|USD",
            "цены в долл. США|USD",
            "цены в евро|EUR",
            "цены в юанях|CNY",
            "цены в у.е. по курсу ЦБ на день оплаты|UE",
            "цены в USD|USD",
            "Валюта предложения: тенге|KZT",
            "Валюта: RUB|RUB",
            "Валюта платежа — доллары США|USD",
    })
    void currencyInPhrase(String text, String code) {
        assertThat(MoneyText.textCurrencies(text)).as(text).containsExactly(code);
    }

    /**
     * Не валюта (ложные срабатывания прежнего правила «слово где угодно»): рядом нет числа и нет явной фразы; адрес
     * почты или сайта; товар и стандарт с дефисом; топоним.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "Тел./ТГ: +7 912 555 01 02",
            "Реагенты: ХС, ТГ, ЛПВП",
            "модель ТГ-12",
            "Комплект: 2 ТГ-анализатора",
            "Кабель питания, вилка евро",
            "Двигатель стандарт Евро-3",
            "Двигатель 2024 Евро-5",
            "Сайт www.rub-med.ru",
            "Почта info@usd-group.kz",
            "eur@x.kz",
            "e-mail: usd2020@mail.ru",
            "www.kzt2024.kz",
            "Москва, Рублёвское ш., д. 5",
            "Москва, корп. 5 Рублёвское шоссе",
            "Гель противорубцовый; рубцы; Рубцовск; рубеж; рубин",
            "вилка евростандарта, Neurosoft, Europe, rubber, Долли",
            "Принимаем ₸ и ₽, € и ¥",
    })
    void notCurrency(String text) {
        assertThat(MoneyText.textCurrencies(text)).as(text).isEmpty();
    }

    /** Реквизиты (ИИК, IBAN, р/с, БИК, SWIFT, номер счёта) — до конца своей фразы: валюта счёта — не валюта цен. */
    @ParameterizedTest
    @ValueSource(strings = {
            "ИИК KZ86 125K ZT10 0410 0100 (KZT), ИИК KZ75 125K ZT10 0410 0200 (USD)",
            "KZ26 998R TB00 0147 3655 тенге, KZ68 998R TB00 0147 3675 рубли",
            "Р/с 40702810400000012345 руб., к/с 30101810400000000225",
            "Расчётный счёт 40702810400000012345 руб.",
            "БИК: TSESKZKA, SWIFT: TSESKZKA",
    })
    void requisitesNotCurrency(String text) {
        assertThat(MoneyText.textCurrencies(text)).as(text).isEmpty();
    }

    /** Реквизиты вырезаются до конца фразы, а не вся строка: «Цены в рублях» перед ними — валюта. */
    @Test
    void requisitesCutToClauseEnd() {
        assertThat(MoneyText.textCurrencies("Цены указаны в рублях. Р/с 40702810400000012345 в ПАО «Банк» 5 руб."))
                .containsExactly("RUB");
    }

    /** Курс («по курсу …», «курс доллара …», «1 USD = 480 тенге») — до конца фразы: валюта курса — не валюта цен. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Цены в тенге по курсу 1 USD = 480 тенге|KZT",
            "Цены в тенге (1 USD = 480 тенге)|KZT",
            "Цены в долларах; курс доллара — 480 тг|USD",
            "Предложение действительно при курсах валют не выше 90 руб. за доллар; цены в тенге|KZT",
            "Оплата в рублях по курсу ЦБ на день оплаты, цены в долларах|RUB USD",
    })
    void rateNotCurrency(String text, String codes) {
        assertThat(MoneyText.textCurrencies(text)).as(text).containsExactlyInAnyOrder(codes.split(" "));
    }

    /**
     * Суммы в тысячах / миллионах («18,4 тыс. руб.», «50 т.р.») — валюта не ясна (MIXED): цены могут быть в тысячах,
     * рублями их считать нельзя — цена вышла бы в 1000 раз меньше.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Итого 18,4 тыс. руб.", "Стоимость 50 т.р.", "Стоимость 50 т. р.", "Итого 1,2 млн тенге",
            "Цены указаны в тыс. руб.", "Итого 12 тыс.$"})
    void thousands_unclear(String text) {
        assertThat(MoneyText.textCurrencies(text)).as(text).containsExactly(MoneyText.MIXED);
    }

    @Test
    void emptyAndLabel() {
        assertThat(MoneyText.textCurrencies(null)).isEmpty();
        assertThat(MoneyText.textCurrencies("  ")).isEmpty();
        assertThat(MoneyText.label(MoneyText.MIXED)).isEqualTo("(валюта?)");
    }

    /** Фрагмент для людей: короткая строка — целиком, длинная — окно до 120 символов вокруг находки, по словам, с «…». */
    @Test
    void fragmentAroundHit() {
        String shortLine = "Общая стоимость — 18 450,00 руб.";
        assertThat(MoneyText.fragment(shortLine, MoneyText.textHits(shortLine).get(0), 120)).isEqualTo(shortLine);

        String longLine = "Предлагаем к поставке оборудование для оснащения кабинета ".repeat(3)
                + "на общую сумму 18 450,00 руб., НДС не облагается, " + "поставка в течение 30 дней с даты оплаты ".repeat(3);
        MoneyText.TextHit h = MoneyText.textHits(longLine).get(0);
        String f = MoneyText.fragment(longLine, h, 120);
        assertThat(f).startsWith("…").endsWith("…").contains("18 450,00 руб.");
        assertThat(f.length()).isLessThanOrEqualTo(122);
        assertThat(f.substring(1, 2)).isNotBlank();   // с начала слова, без пробела после «…»
    }
}
