package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierReplyDeclineDetectorTest {

    /** Реальное письмо ТОО «Искра Трэйдинг» (КП-539): отказ сверху, наш процитированный оригинал ниже. */
    private static final String ISKRA = String.join("\n",
            "Добрый день,",
            "",
            "данную позицию мы не поставляем, к сожалению. На рынке достаточно поставщиков",
            "",
            "С уважением,",
            "ТОО \"Искра Трэйдинг\"",
            "Манай Токушев",
            "Коммерческий директор",
            "Tel: +7 (727) 269 70 23",
            "manay@iskra.kz",
            "www.iskra.kz",
            "===============  Original  Message =============================",
            "You wrote on Friday, July 10, 2026, 16:29:39:",
            "From: zakup@westmed.kz zakup@westmed.kz",
            "Subject: [КП-539] Запрос коммерческого предложения",
            "",
            "Здравствуйте!",
            "ТОО «West-Med» просит предоставить коммерческое предложение по следующим позициям:",
            "— Аппарат ультразвуковой оториноларингологический ТОНЗИЛЛОР — 1 шт.",
            "Просим указать: цену за единицу, сроки поставки, условия оплаты, гарантию.");

    @Test
    void realIskraRefusal_detectedAsDecline() {
        assertThat(SupplierReplyDeclineDetector.isDecline(ISKRA)).isTrue();
    }

    @Test
    void priceOffer_notDecline() {
        assertThat(SupplierReplyDeclineDetector.isDecline(
                "Здравствуйте! Стоимость аппарата 3 450 000 тенге, срок поставки 20 рабочих дней.")).isFalse();
    }

    @Test
    void discontinued_detected() {
        assertThat(SupplierReplyDeclineDetector.isDecline(
                "К сожалению, данная модель снята с производства.")).isTrue();
        assertThat(SupplierReplyDeclineDetector.isDecline(
                "Мы этим оборудованием не занимаемся.")).isTrue();
    }

    @Test
    void weakSignalsAlone_notDecline() {
        // «к сожалению» / «на рынке достаточно поставщиков» без явной формулы отказа — НЕ триггерят
        assertThat(SupplierReplyDeclineDetector.isDecline(
                "К сожалению, ответим чуть позже — на рынке достаточно поставщиков подобного.")).isFalse();
    }

    @Test
    void declinePhraseOnlyInQuotedOriginal_ignored() {
        // фраза-отказ ТОЛЬКО в процитированном нашем письме (ниже «From:») — не наш ответ, игнор
        String body = String.join("\n",
                "Добрый день, спасибо за запрос, подготовим предложение.",
                "From: zakup@westmed.kz",
                "мы не поставляем это дешевле рынка");  // в цитате — не считается
        assertThat(SupplierReplyDeclineDetector.isDecline(body)).isFalse();
    }

    @Test
    void blankOrNull_false() {
        assertThat(SupplierReplyDeclineDetector.isDecline(null)).isFalse();
        assertThat(SupplierReplyDeclineDetector.isDecline("   ")).isFalse();
    }
}
