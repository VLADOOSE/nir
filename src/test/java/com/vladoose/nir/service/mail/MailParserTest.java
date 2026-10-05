package com.vladoose.nir.service.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.vladoose.nir.service.mail.TestMimes.*;
import static org.assertj.core.api.Assertions.assertThat;

class MailParserTest {

    @Test
    void plainLetter_cyrillicSender_subjectMessageIdText() throws Exception {
        MimeMessage m = roundTrip(plain("Иван Петров <ivan@medtech.kz>", "Re: [КП-534] Запрос", "Цена 100 тенге"));

        ParsedMail p = MailParser.parse(m, 7);

        assertThat(p.uid()).isEqualTo(7);
        assertThat(p.from()).isEqualTo("Иван Петров <ivan@medtech.kz>");
        assertThat(p.fromAddress()).isEqualTo("ivan@medtech.kz");
        assertThat(p.subject()).isEqualTo("Re: [КП-534] Запрос");
        assertThat(p.messageId()).startsWith("<").endsWith(">");
        assertThat(p.text()).contains("Цена 100 тенге");
        assertThat(p.html()).isEmpty();
        assertThat(p.attachmentNames()).isEmpty();
        assertThat(p.bounce()).isNull();
        assertThat(p.contentType()).startsWith("text/plain");
    }

    @Test
    void htmlOnly_bodyWithoutStyleBlocks() throws Exception {
        MimeMessage m = roundTrip(html("s@x.kz", "Цена",
                "<html><head><style>p{color:red}</style></head><body><p>Добрый день!</p><p>Цена 5 000 тг</p></body></html>"));

        ParsedMail p = MailParser.parse(m, 1);

        assertThat(p.text()).isEmpty();
        assertThat(p.body()).contains("Добрый день!").contains("Цена 5 000 тг").doesNotContain("color");
    }

    @Test
    void nestedAlternativeWithEncodedExcel_textExcelAndName() throws Exception {
        MimeMultipart alt = new MimeMultipart("alternative");
        MimeBodyPart t = new MimeBodyPart();
        t.setText("Прошу выставить КП по списку", "UTF-8");
        MimeBodyPart h = new MimeBodyPart();
        h.setContent("<p>Прошу выставить КП по списку</p>", "text/html; charset=UTF-8");
        alt.addBodyPart(t);
        alt.addBodyPart(h);
        MimeBodyPart altPart = new MimeBodyPart();
        altPart.setContent(alt);
        MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new DataHandler(new ByteArrayDataSource(xlsxBytes(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")));
        file.setFileName(MimeUtility.encodeText("Список ТХ.xlsx", "UTF-8", "B"));
        file.setDisposition(MimeBodyPart.ATTACHMENT);
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(altPart);
        mixed.addBodyPart(file);
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("clinic@x.kz"));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress("zakup@westmed.kz"));
        m.setSubject("Заявка", "UTF-8");
        m.setContent(mixed);
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.text()).contains("Прошу выставить КП");
        assertThat(p.excelBytes()).isNotNull();
        assertThat(p.excelName()).isEqualTo("Список ТХ.xlsx");
        assertThat(p.attachmentNames()).containsExactly("Список ТХ.xlsx");
    }

    @Test
    void inlineSignatureImage_isNotAttachment_pdfIs() throws Exception {
        MimeMultipart related = new MimeMultipart("related");
        MimeBodyPart h = new MimeBodyPart();
        h.setContent("<p>Цена в файле</p><img src=\"cid:logo1\">", "text/html; charset=UTF-8");
        related.addBodyPart(h);
        MimeBodyPart img = new MimeBodyPart();
        img.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{1, 2, 3}, "image/png")));
        img.setContentID("<logo1>");
        img.setDisposition(MimeBodyPart.INLINE);
        img.setFileName("image001.png");
        related.addBodyPart(img);
        MimeBodyPart relPart = new MimeBodyPart();
        relPart.setContent(related);
        MimeBodyPart pdf = new MimeBodyPart();
        pdf.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{4, 5}, "application/pdf")));
        pdf.setFileName(MimeUtility.encodeText("КП.pdf", "UTF-8", "B"));
        pdf.setDisposition(MimeBodyPart.ATTACHMENT);
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(relPart);
        mixed.addBodyPart(pdf);
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("s@x.kz"));
        m.setSubject("КП", "UTF-8");
        m.setContent(mixed);
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.attachmentNames()).containsExactly("КП.pdf");
        assertThat(p.body()).contains("Цена в файле");
    }

    @Test
    void dsnWithOriginalMessage_bounceFields_andOriginalTextNotMerged() throws Exception {
        MimeMessage m = roundTrip(dsn("[КП-534] Запрос КП", "sales@medtech.kz",
                "550 5.1.1 <sales@medtech.kz>:\r\n    Recipient address rejected: User unknown"));

        ParsedMail p = MailParser.parse(m, 3);

        assertThat(p.contentType()).startsWith("multipart/report").contains("delivery-status");
        assertThat(p.fromAddress()).isEqualTo("mailer-daemon@corp.mail.ru");
        assertThat(p.bounce()).isNotNull();
        assertThat(p.bounce().finalRecipient()).isEqualTo("sales@medtech.kz");
        assertThat(p.bounce().status()).isEqualTo("5.1.1");
        assertThat(p.bounce().diagnostic()).contains("550 5.1.1").contains("User unknown");
        assertThat(p.bounce().originalSubject()).isEqualTo("[КП-534] Запрос КП");
        assertThat(p.text()).contains("could not be delivered").doesNotContain("Просим коммерческое");
    }

    @Test
    void dsnWithHeadersOnly_decodesEncodedSubject() throws Exception {
        ParsedMail p = MailParser.parse(roundTrip(dsnHeadersOnly("[КП-77] Запрос коммерческого предложения")), 1);

        assertThat(p.bounce()).isNotNull();
        assertThat(p.bounce().originalSubject()).isEqualTo("[КП-77] Запрос коммерческого предложения");
    }

    @Test
    void autoReplyHeaders_lowercased() throws Exception {
        MimeMessage m = plain("s@x.kz", "Автоответ", "Я в отпуске");
        m.setHeader("Auto-Submitted", "Auto-Replied");
        m.setHeader("X-Autoreply", "yes");
        m.setHeader("Precedence", "Bulk");
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.autoHeaders()).containsEntry("auto-submitted", "auto-replied")
                .containsEntry("x-autoreply", "yes").containsEntry("precedence", "bulk");
    }

    @Test
    void unknownCharset_doesNotThrow_readsBytes() throws Exception {
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("s@x.kz"));
        m.setSubject("Кодировка", "UTF-8");
        m.setDataHandler(new DataHandler(new ByteArrayDataSource("Привет".getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=x-unknown-777")));
        m.setHeader("Content-Type", "text/plain; charset=x-unknown-777");
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.text()).contains("Привет");
    }

    @Test
    void addressPart_cases() {
        assertThat(MailParser.addressPart("Иван <Ivan@X.kz>")).isEqualTo("ivan@x.kz");
        assertThat(MailParser.addressPart("a@b.kz")).isEqualTo("a@b.kz");
        assertThat(MailParser.addressPart(null)).isEmpty();
    }
}
