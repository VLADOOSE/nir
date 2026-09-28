package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** Текст кнопки WhatsApp на westmed.kz: WhatsAppButton.tsx + messages/{ru,kz,en}.json сайта (спека §6.7). */
class SiteCartMessageParserTest {

    @Test
    void russianTemplateWithQuantities() {
        String text = """
                Здравствуйте! Интересует следующее оборудование:

                1. Облучатель ОБН-150 (x2)
                2. Рециркулятор СН-111-130

                Прошу подготовить коммерческое предложение.""";

        assertThat(SiteCartMessageParser.parse(text)).containsExactly(
                new SiteCartMessageParser.Line("Облучатель ОБН-150", 2),
                new SiteCartMessageParser.Line("Рециркулятор СН-111-130", 1));
    }

    @Test
    void kazakhAndEnglishTemplates() {
        assertThat(SiteCartMessageParser.parse("Сәлеметсіз бе! Келесі жабдық қызықтырады:\n\n1. Аппарат УЗИ (x3)\n\nКоммерциялық ұсыныс дайындауыңызды сұраймын."))
                .containsExactly(new SiteCartMessageParser.Line("Аппарат УЗИ", 3));
        assertThat(SiteCartMessageParser.parse("Hello! I'm interested in the following equipment:\n\n1. Autoclave\n\nPlease prepare a commercial offer."))
                .containsExactly(new SiteCartMessageParser.Line("Autoclave", 1));
    }

    @Test
    void spacesCaseAndWindowsLineBreaksDoNotMatter() {
        assertThat(SiteCartMessageParser.parse("  здравствуйте! интересует следующее оборудование:\r\n\r\n 1.  Облучатель  (x2) \r\n"))
                .containsExactly(new SiteCartMessageParser.Line("Облучатель", 2));
    }

    @Test
    void freeNumberedListOfClientIsNotTemplate() {
        assertThat(SiteCartMessageParser.parse("1. срочно\n2. доставка в Уральск")).isEmpty();
        assertThat(SiteCartMessageParser.parse("Здравствуйте, нужен аппарат УЗИ")).isEmpty();
        assertThat(SiteCartMessageParser.parse(null)).isEmpty();
    }

    @Test
    void greetingWithoutLinesGivesNothingAndHugeQuantityStaysInName() {
        assertThat(SiteCartMessageParser.parse("Здравствуйте! Интересует следующее оборудование:\n\nПрошу подготовить коммерческое предложение."))
                .isEmpty();
        assertThat(SiteCartMessageParser.parse("Здравствуйте! Интересует следующее оборудование:\n1. Бахилы (x123456)"))
                .containsExactly(new SiteCartMessageParser.Line("Бахилы (x123456)", 1));
    }
}
