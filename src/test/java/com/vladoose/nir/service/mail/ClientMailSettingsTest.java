package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientMailSettingsTest {

    static ClientMailSettings settings(boolean enabled, String user, String market, String zakup, String domains, int fileMb) {
        return new ClientMailSettings(enabled, "imap.mail.ru", 993, user, "pw", "imaps", market, 7, fileMb, domains,
                "info@westmed.kz", zakup);
    }

    @Test
    void enabled_derivesOwnDomainRulesAndLimit() {
        ClientMailSettings s = settings(true, " Info@WestMed.kz ", "kz", "zakup@westmed.kz", " *.mail.ru , 2gis.ru,, ", 25);

        assertThat(s.enabled()).isTrue();
        assertThat(s.username()).isEqualTo("info@westmed.kz");
        assertThat(s.market()).isEqualTo(Market.KZ);
        assertThat(s.rules().ownDomain()).isEqualTo("westmed.kz");
        assertThat(s.rules().siteNotificationFrom()).isEqualTo("info@westmed.kz");
        assertThat(s.rules().serviceDomains()).containsExactly("*.mail.ru", "2gis.ru");
        assertThat(s.maxFileBytes()).isEqualTo(25 * 1024 * 1024);
    }

    @Test
    void sameMailboxAsZakup_disabled() {
        assertThat(settings(true, "zakup@westmed.kz", "KZ", "Zakup@westmed.kz", "", 25).enabled()).isFalse();
    }

    @Test
    void unknownMarket_disabled() {
        assertThat(settings(true, "info@westmed.kz", "KZT", "zakup@westmed.kz", "", 25).enabled()).isFalse();
    }

    @Test
    void blankUser_disabled() {
        assertThat(settings(true, " ", "KZ", "zakup@westmed.kz", "", 25).enabled()).isFalse();
    }

    @Test
    void fileLimitBetweenOneAndHundredMb() {
        assertThat(settings(false, "info@westmed.kz", "KZ", "z@w.kz", "", 500).maxFileBytes()).isEqualTo(100 * 1024 * 1024);
        assertThat(settings(false, "info@westmed.kz", "KZ", "z@w.kz", "", 0).maxFileBytes()).isEqualTo(1024 * 1024);
    }
}
