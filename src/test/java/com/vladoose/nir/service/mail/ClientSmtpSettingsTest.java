package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SMTP ящика клиентов (спека 2026-10-19-client-mail-reply §3.1). */
class ClientSmtpSettingsTest {

    static ClientSmtpSettings s(String host, String user, String password, String market) {
        return new ClientSmtpSettings(host, 465, true, user, password, market, "", true);
    }

    @Test
    void enabled_onlyWithHostAddressPasswordAndMarket() {
        assertThat(s("smtp.mail.ru", "info@westmed.kz", "p", "KZ").enabled()).isTrue();
        assertThat(s("", "info@westmed.kz", "p", "KZ").enabled()).isFalse();
        assertThat(s("smtp.mail.ru", "info", "p", "KZ").enabled()).isFalse();
        assertThat(s("smtp.mail.ru", "info@westmed.kz", "", "KZ").enabled()).isFalse();
        assertThat(s("smtp.mail.ru", "info@westmed.kz", "p", "XX").enabled()).isFalse();   // опечатка — не RF
    }

    @Test
    void problem_notConfigured_otherMarket_ok() {
        assertThat(s("", "info@westmed.kz", "p", "KZ").problem(Market.KZ)).isEqualTo(ClientSmtpSettings.NOT_CONFIGURED);
        assertThat(s("smtp.mail.ru", "info@westmed.kz", "p", "KZ").problem(Market.RF))
                .isEqualTo("Письма клиентам — только на рынке KZ (ящик info@westmed.kz)");
        assertThat(s("smtp.mail.ru", " Info@WestMed.kz ", "p", "KZ").problem(Market.KZ)).isNull();
    }

    @Test
    void username_lowercased_fromNameDefault() {
        ClientSmtpSettings x = s("smtp.mail.ru", " Info@WestMed.kz ", "p", "kz");
        assertThat(x.username()).isEqualTo("info@westmed.kz");
        assertThat(x.fromName()).isEqualTo("West-Med");
        assertThat(x.market()).isEqualTo(Market.KZ);
    }
}
