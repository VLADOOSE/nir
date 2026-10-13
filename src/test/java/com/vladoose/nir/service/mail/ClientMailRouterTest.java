package com.vladoose.nir.service.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.vladoose.nir.service.mail.ClientMailRoute.*;
import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

class ClientMailRouterTest {

    static final ClientMailRules RULES = new ClientMailRules("info@westmed.kz", "westmed.kz",
            List.of("*.mail.ru", "2gis.ru", "2gis.com", "ecc.kz", "goszakup.gov.kz"));

    static ClientMailRoute route(TestMails.Builder b) {
        return ClientMailRouter.route(b.build(), RULES);
    }

    @Test
    void clientLetter_isConversation() {
        assertThat(route(mail().from("Айгуль <aigul@clinic-uralsk.kz>").subject("Запрос цены"))).isEqualTo(CONVERSATION);
    }

    /** Уведомление сайта приходит С info@ — правило 1 раньше «своего домена». */
    @Test
    void siteNotification_beforeOwnDomain() {
        assertThat(route(mail().from("Сайт <info@westmed.kz>").subject("Новая заявка — westmed.kz"))).isEqualTo(SITE_NOTIFICATION);
        assertThat(route(mail().from("info@westmed.kz").subject("Пересылка"))).isEqualTo(OWN_DOMAIN);
    }

    @Test
    void reports_bounceDelayedAutoReplyReadReceipt() {
        assertThat(route(mail().from("MAILER-DAEMON@corp.mail.ru").contentType("multipart/report; report-type=delivery-status")
                .bounce("a@b.kz", "5.1.1", "550", "Запрос", "failed"))).isEqualTo(BOUNCE);
        assertThat(route(mail().from("mailer-daemon@corp.mail.ru").contentType("multipart/report; report-type=delivery-status")
                .bounce("a@b.kz", "4.4.1", "451", "Запрос", "delayed"))).isEqualTo(DELAYED);
        assertThat(route(mail().from("Анна <anna@clinic.kz>").header("Auto-Submitted", "auto-replied"))).isEqualTo(AUTO_REPLY);
        assertThat(route(mail().from("anna@clinic.kz").contentType("multipart/report; report-type=disposition-notification")))
                .isEqualTo(AUTO_REPLY);
        assertThat(route(mail().from("postmaster@clinic.kz"))).isEqualTo(BOUNCE);      // почтовый робот — правило возврата
    }

    @Test
    void newsletter_byListHeadersOrPrecedence() {
        assertThat(route(mail().from("news@shop.kz").header("List-Unsubscribe", "<mailto:u@shop.kz>"))).isEqualTo(NEWSLETTER);
        assertThat(route(mail().from("news@shop.kz").header("List-Id", "news.shop.kz"))).isEqualTo(NEWSLETTER);
        for (String p : List.of("bulk", "list", "junk")) {
            assertThat(route(mail().from("news@shop.kz").header("Precedence", p))).isEqualTo(NEWSLETTER);
        }
        // рассылка со служебного адреса — всё равно рассылка: правило 3 раньше 4
        assertThat(route(mail().from("noreply@shop.kz").header("List-Id", "news.shop.kz"))).isEqualTo(NEWSLETTER);
    }

    @Test
    void service_byLocalPart() {
        for (String local : List.of("noreply", "no-reply", "not-reply", "donotreply", "do-not-reply", "NoReply")) {
            assertThat(route(mail().from(local + "@shop.kz"))).as(local).isEqualTo(SERVICE);
        }
    }

    @Test
    void personalMailRuIsConversation_subdomainIsService() {
        assertThat(route(mail().from("Иван <ivanov@mail.ru>"))).isEqualTo(CONVERSATION);
        assertThat(route(mail().from("client@bk.ru"))).isEqualTo(CONVERSATION);
        assertThat(route(mail().from("security@id.mail.ru"))).isEqualTo(SERVICE);
        assertThat(route(mail().from("info@corp.mail.ru"))).isEqualTo(SERVICE);
    }

    @Test
    void service_byDomainWithSubdomains() {
        assertThat(route(mail().from("help@2gis.ru"))).isEqualTo(SERVICE);
        assertThat(route(mail().from("robot@info.2gis.com"))).isEqualTo(SERVICE);
        assertThat(route(mail().from("fms@ecc.kz"))).isEqualTo(SERVICE);
        assertThat(route(mail().from("portal@v3bl.goszakup.gov.kz"))).isEqualTo(SERVICE);
        assertThat(route(mail().from("buyer@notgoszakup.gov.kz"))).isEqualTo(CONVERSATION);   // похожее имя — не поддомен
    }

    @Test
    void ownDomainAndSubdomain() {
        assertThat(route(mail().from("Бухгалтерия <buh@westmed.kz>"))).isEqualTo(OWN_DOMAIN);
        assertThat(route(mail().from("robot@mail.westmed.kz"))).isEqualTo(OWN_DOMAIN);
    }

    @Test
    void noSenderAddress_isService() {
        assertThat(route(mail().from(""))).isEqualTo(SERVICE);
    }

    /** То же для адресатов наших писем из «Отправленных». */
    @Test
    void recipientsOfOurLetters() {
        assertThat(ClientMailRouter.isService("support@corp.mail.ru", RULES)).isTrue();
        assertThat(ClientMailRouter.isService("noreply@shop.kz", RULES)).isTrue();
        assertThat(ClientMailRouter.isService("client@mail.ru", RULES)).isFalse();
        assertThat(ClientMailRouter.isOwnDomain("manager@westmed.kz", RULES)).isTrue();
        assertThat(ClientMailRouter.isOwnDomain("client@westmed.kz.evil.com", RULES)).isFalse();
    }
}
