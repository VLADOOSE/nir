package com.vladoose.nir.service.mail;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Копия письма клиенту — в «Отправленные» info@ (спека 2026-10-19-client-mail-reply §3.4). */
class ClientSentCopyTest {

    static final String INFO = "info@westmed.kz";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(INFO, "secret"))
            .withPerMethodLifecycle(true);

    @BeforeEach
    void sentFolder() throws Exception {
        GreenMailUser u = greenMail.getManagers().getUserManager().getUser(INFO);
        greenMail.getManagers().getImapHostManager().createMailbox(u, "Sent");
    }

    static ClientMailSettings imap() {
        return new ClientMailSettings(true, "127.0.0.1", ServerSetupTest.IMAP.getPort(), INFO, "secret", "imap", "KZ",
                7, 25, "", INFO, "zakup@westmed.kz");
    }

    static ClientSmtpSettings smtp(boolean copy) {
        return new ClientSmtpSettings("127.0.0.1", ServerSetupTest.SMTP.getPort(), false, INFO, "secret", "KZ", "West-Med", copy);
    }

    private static MimeMessage sent() throws Exception {
        ClientMailSender sender = new ClientMailSender(smtp(true));
        MimeMessage m = ClientLetterBuilder.build(sender.session(), new ClientLetter("West-Med", INFO, List.of("zz@clinic.kz"),
                "Re: Запрос", "Текст", "", null, null, ClientLetterBuilder.messageId(INFO), List.of()), ZoneId.of("Asia/Oral"));
        sender.send(m);
        return m;
    }

    @Test
    void copyLandsInInfoSent_sameMessageId_markedRead() throws Exception {
        MimeMessage m = sent();
        new ClientSentCopy(smtp(true), imap(), Runnable::run).store(m);
        assertThat(SentCopyTest.folder(INFO, "Sent")).containsExactly(new SentCopyTest.Stored("Re: Запрос", m.getMessageID(), true));
    }

    @Test
    void switchedOff_noCopy() throws Exception {
        MimeMessage m = sent();
        new ClientSentCopy(smtp(false), imap(), Runnable::run).store(m);
        assertThat(SentCopyTest.folder(INFO, "Sent")).isEmpty();
    }
}
