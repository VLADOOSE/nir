package com.vladoose.nir.util;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.util.SupplierReplyPriceParser.ParsedPrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierReplyPriceParserTest {

    private BigDecimal price(String body, Market m) {
        Optional<ParsedPrice> r = SupplierReplyPriceParser.parse(body, m);
        assertThat(r).as("ожидалась цена в: %s", body).isPresent();
        return r.get().price();
    }

    @Test
    void realReply_priceWithCurrency_andStripsOurQuotedOriginal() {
        String body = "Цена 3 200 000 ₸, срок 3 недели\r\n\r\n"
                + "On Fri, Jul 10, 2026 at 1:42 PM <zakup@westmed.kz> wrote:\r\n"
                + "> Здравствуйте!\r\n"
                + "> — Лот —: ... РУ № РК МИ (МТ)-0№030788 — 1 шт.\r\n"
                + "> Просим ответить до 13.07.2026.\r\n";
        Optional<ParsedPrice> r = SupplierReplyPriceParser.parse(body, Market.KZ);
        assertThat(r).isPresent();
        assertThat(r.get().price()).isEqualByComparingTo("3200000");
        assertThat(r.get().term()).contains("3").containsIgnoringCase("недел");
    }

    @Test
    void decline_noPrice_returnsEmpty() {
        String body = "Здравствуйте! Спасибо за обращение. "
                + "Запрашиваемые вами изделия мы не поставляем.\r\n"
                + "Моб/WhatsApp: +7 700 025-88-50";
        assertThat(SupplierReplyPriceParser.parse(body, Market.KZ)).isEmpty();
    }

    @Test
    void counterQuestion_noPrice_returnsEmpty() {
        String body = "Добрый день. 1. Какого объёма нужен холодильник? "
                + "2. Со стеклянной или металической дверью?";
        assertThat(SupplierReplyPriceParser.parse(body, Market.KZ)).isEmpty();
    }

    @Test
    void variousFormats() {
        assertThat(price("Наша цена 3200000 тенге", Market.KZ)).isEqualByComparingTo("3200000");
        assertThat(price("Стоимость: 3 200 000,00 тг", Market.KZ)).isEqualByComparingTo("3200000.00");
        assertThat(price("цена 3200000", Market.KZ)).isEqualByComparingTo("3200000");
        assertThat(price("Предлагаем 2 950 000 ₸", Market.KZ)).isEqualByComparingTo("2950000");
    }

    @Test
    void ignoresPhoneNumbers_whenNoPrice() {
        assertThat(SupplierReplyPriceParser.parse("Звоните: +7 700 025-88-50", Market.KZ)).isEmpty();
    }

    @Test
    void picksPrice_notPhone_whenBothPresent() {
        String body = "Цена 3 200 000 ₸. Тел: +7 700 025-88-50";
        assertThat(price(body, Market.KZ)).isEqualByComparingTo("3200000");
    }

    @Test
    void htmlBody_stripsTags_findsPrice() {
        String body = "<div>Цена <b>3 200 000</b>&nbsp;₸</div>";
        assertThat(price(body, Market.KZ)).isEqualByComparingTo("3200000");
    }

    @Test
    void rfMarket_rubles() {
        assertThat(price("цена 450 000 руб", Market.RF)).isEqualByComparingTo("450000");
    }

    @Test
    void bareNumberWithoutAnchor_returnsEmpty() {
        // число есть, но нет ни валюты, ни слова-якоря → не гадаем
        assertThat(SupplierReplyPriceParser.parse("Отгрузим 3200000 штук со склада", Market.KZ)).isEmpty();
    }

    @Test
    void nullOrBlank_returnsEmpty() {
        assertThat(SupplierReplyPriceParser.parse(null, Market.KZ)).isEmpty();
        assertThat(SupplierReplyPriceParser.parse("   ", Market.KZ)).isEmpty();
    }

    @Test
    void overlyLongNumber_returnsEmpty() {
        // >13 целых цифр → overflow NUMERIC(15,2); парсер не должен такое отдавать (иначе откат poll)
        assertThat(SupplierReplyPriceParser.parse("Сумма 99999999999999999 ₸", Market.KZ)).isEmpty();
    }

    @Test
    void stripsMailRuAttributionQuote_notJustGmail() {
        String body = "Цена 5 000 000 ₸\r\n\r\n"
                + "10 июля 2026 г., 13:42 пользователь West-Med <zakup@westmed.kz> пишет:\r\n"
                + "старая цена 9 999 999 ₸\r\n";
        assertThat(price(body, Market.KZ)).isEqualByComparingTo("5000000");
    }

    @Test
    void prefersUnitPrice_overGrandTotal() {
        assertThat(price("Цена за единицу 3 200 000 ₸, итого 6 400 000 ₸", Market.KZ))
                .isEqualByComparingTo("3200000");
    }

    @Test
    void skipsQuantityWithUnit() {
        assertThat(SupplierReplyPriceParser.parse("Отгрузим 5000 шт, цена договорная", Market.KZ)).isEmpty();
    }
}
