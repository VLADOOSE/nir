package com.vladoose.nir.service;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.support.SmtpIntegrationTest;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Письмо на несколько адресов и вложение с кириллическим именем — сквозь настоящий SMTP (GreenMail). */
@SmtpIntegrationTest
class EmailServiceTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("zakup-test@westmed.local", "secret"));

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired EmailService emailService;

    /** Текст письма — в любой глубине multipart (MimeMessageHelper кладёт его в related внутри mixed). */
    static String text(Part p) throws Exception {
        if (p.isMimeType("text/plain") && !Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) return (String) p.getContent();
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String t = text(mp.getBodyPart(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    static Part attachment(Part p) throws Exception {
        if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) return p;
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                Part a = attachment(mp.getBodyPart(i));
                if (a != null) return a;
            }
        }
        return null;
    }

    @Test
    void sendsToAllRecipientsWithCyrillicAttachmentName() throws Exception {
        byte[] doc = {1, 2, 3, 4};
        String name = "Запрос КП № 1 от 12.10.2026 — West-Med.docx";

        emailService.sendEmail(List.of("a@ema.test", "b@ema.test"), "[КП-1] Тема", "Текст письма",
                List.of(new EmailService.Attachment(name, doc, DOCX)));

        MimeMessage[] mails = greenMail.getReceivedMessages();
        assertThat(mails).hasSize(2);   // GreenMail кладёт копию каждому адресату
        MimeMessage m = mails[0];
        assertThat(Arrays.stream(m.getRecipients(Message.RecipientType.TO)).map(Address::toString))
                .containsExactly("a@ema.test", "b@ema.test");
        assertThat(text(m)).isEqualTo("Текст письма");
        Part a = attachment(m);
        assertThat(a).isNotNull();
        assertThat(MimeUtility.decodeText(a.getFileName())).isEqualTo(name);
        assertThat(a.getContentType()).startsWith(DOCX);
        assertThat(a.getInputStream().readAllBytes()).isEqualTo(doc);
    }

    @Test
    void withoutAttachmentsTheLetterIsPlainText() throws Exception {
        emailService.sendEmail("one@ema.test", "Тема", "Просто текст");
        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(m.getContent()).isEqualTo("Просто текст");
    }
}
