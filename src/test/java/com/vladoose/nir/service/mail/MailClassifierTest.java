package com.vladoose.nir.service.mail;

import org.junit.jupiter.api.Test;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

class MailClassifierTest {

    static final ClassifierRules ZAKUP = new ClassifierRules("zakup@westmed.kz", "info@westmed.kz", false);
    static final ClassifierRules INFO = new ClassifierRules("zakup@westmed.kz", "info@westmed.kz", true);

    @Test
    void siteNotification_skipped() {
        Classification c = MailClassifier.classify(mail().from("WestMed.kz <info@westmed.kz>")
                .subject("Запрос КП (2 поз.) — westmed.kz").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.SITE_NOTIFICATION);
    }

    @Test
    void ownEcho_withToken_isOwn() {
        Classification c = MailClassifier.classify(mail().from("zakup@westmed.kz").subject("[КП-5] Запрос").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.OWN);
    }

    @Test
    void dsnContentType_isBounce_tokenFromOriginalSubject() {
        Classification c = MailClassifier.classify(mail().from("MAILER-DAEMON@corp.mail.ru")
                .subject("Undelivered Mail Returned to Sender")
                .contentType("multipart/report; report-type=delivery-status; boundary=\"x\"")
                .bounce("sales@x.kz", "5.1.1", "550 User unknown", "[КП-534] Запрос КП").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.BOUNCE);
        assertThat(c.kpId()).isEqualTo(534L);
    }

    @Test
    void quotedReportType_isBounce() {
        Classification c = MailClassifier.classify(mail().from("robot@x.kz")
                .contentType("multipart/report; report-type=\"delivery-status\"").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.BOUNCE);
    }

    @Test
    void undeliverableFromPostmaster_isBounce_notSupplierResponse() {
        Classification c = MailClassifier.classify(mail().from("postmaster@medtech.kz")
                .subject("Undeliverable: [КП-5] Запрос КП").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.BOUNCE);
        assertThat(c.kpId()).isEqualTo(5L);
    }

    @Test
    void bounceToken_fromBodyAsLastResort() {
        Classification c = MailClassifier.classify(mail().from("mailer-daemon@x.kz").subject("Mail failure")
                .text("Original subject: [КП-12] Запрос").build(), ZAKUP);
        assertThat(c.kpId()).isEqualTo(12L);
    }

    /**
     * Отчёт об отложенной доставке (Action: delayed — сервер получателя ещё повторяет попытки) — свой вид, а не
     * «не доставлено». Метка запроса — из темы исходного письма, как у возврата.
     */
    @Test
    void delayedDsn_isDelayed_tokenFromOriginalSubject() {
        Classification c = MailClassifier.classify(mail().from("MAILER-DAEMON@corp.mail.ru")
                .subject("Delayed Mail (still being retried)")
                .contentType("multipart/report; report-type=delivery-status; boundary=\"x\"")
                .bounce("sales@x.kz", "4.4.1", "421 4.4.1 Connection timed out", "[КП-534] Запрос КП", "delayed")
                .build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.DELAYED);
        assertThat(c.kpId()).isEqualTo(534L);
    }

    /**
     * Итоговый отказ Postfix после срока очереди: Action: failed с последним ВРЕМЕННЫМ кодом 4.4.1. Задержку решает
     * Action, а не код статуса, — это возврат.
     */
    @Test
    void failedDsnWithTemporaryCode_isBounce() {
        Classification c = MailClassifier.classify(mail().from("MAILER-DAEMON@corp.mail.ru")
                .subject("Undelivered Mail Returned to Sender")
                .contentType("multipart/report; report-type=delivery-status; boundary=\"x\"")
                .bounce("sales@x.kz", "4.4.1", "connect to mx.x.kz: Connection timed out", "[КП-534] Запрос КП", "failed")
                .build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.BOUNCE);
    }

    /** Отчёт без поля Action, даже с временным кодом, — возврат: счесть провал задержкой хуже, чем лишний раз позвать. */
    @Test
    void dsnWithoutAction_isBounce() {
        Classification c = MailClassifier.classify(mail().from("postmaster@x.kz").subject("Mail failure")
                .contentType("multipart/report; report-type=delivery-status; boundary=\"x\"")
                .bounce("a@b.kz", "4.4.1", null, "[КП-7] Запрос КП", null).build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.BOUNCE);
    }

    /**
     * Задержка — вид ВОЗВРАТА. Письмо, которое возвратом не признано (обычный отправитель, не multipart/report),
     * с частью delivery-status внутри — поставщик приложил отчёт своего сервера — идёт по своим правилам.
     */
    @Test
    void delayedStatusPartInOrdinaryLetter_notDelayed() {
        Classification c = MailClassifier.classify(mail().subject("Re: [КП-9] Запрос КП")
                .bounce("a@b.kz", "4.4.1", null, null, "delayed").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.SUPPLIER_RESPONSE);
        assertThat(c.kpId()).isEqualTo(9L);
    }

    @Test
    void autoSubmitted_withToken_isAutoReply() {
        Classification c = MailClassifier.classify(mail().subject("Re: [КП-9] Запрос")
                .header("Auto-Submitted", "auto-replied").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.AUTO_REPLY);
        assertThat(c.kpId()).isEqualTo(9L);
    }

    @Test
    void autoSubmittedNo_isSupplierResponse() {
        Classification c = MailClassifier.classify(mail().subject("Re: [КП-9] Запрос")
                .header("Auto-Submitted", "no").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.SUPPLIER_RESPONSE);
    }

    @Test
    void autoReplySubject_isAutoReply() {
        Classification c = MailClassifier.classify(mail().subject("Автоматический ответ: Re: [КП-9] Запрос").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.AUTO_REPLY);
        assertThat(c.kpId()).isEqualTo(9L);
    }

    @Test
    void xAutoreplyHeader_isAutoReply() {
        assertThat(MailClassifier.classify(mail().header("X-Autoreply", "yes").build(), ZAKUP).mailClass())
                .isEqualTo(MailClass.AUTO_REPLY);
    }

    @Test
    void precedenceBulk_isNotAutoReply() {
        assertThat(MailClassifier.classify(mail().header("Precedence", "bulk").build(), ZAKUP).mailClass())
                .isEqualTo(MailClass.UNMATCHED);
    }

    @Test
    void token_isSupplierResponse() {
        Classification c = MailClassifier.classify(mail().subject("Re: [КП-77] Запрос КП").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.SUPPLIER_RESPONSE);
        assertThat(c.kpId()).isEqualTo(77L);
    }

    @Test
    void excelWithoutToken_dependsOnClientRequests() {
        assertThat(MailClassifier.classify(mail().excel("прайс.xlsx").build(), ZAKUP).mailClass()).isEqualTo(MailClass.UNMATCHED);
        assertThat(MailClassifier.classify(mail().excel("заявка.xlsx").build(), INFO).mailClass()).isEqualTo(MailClass.CLIENT_REQUEST);
    }

    @Test
    void plain_isUnmatched() {
        Classification c = MailClassifier.classify(mail().build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.UNMATCHED);
        assertThat(c.kpId()).isNull();
    }
}
