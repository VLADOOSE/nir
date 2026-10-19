package com.vladoose.nir.service.mail;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;

import java.net.ConnectException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** SMTP ящика клиентов сквозь GreenMail и тексты сбоев (спека 2026-10-19-client-mail-reply §3.5). */
class ClientMailSenderTest {

    static final String INFO = "info@westmed.kz";
    static final ZoneId ORAL = ZoneId.of("Asia/Oral");

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(INFO, "secret"))
            .withPerMethodLifecycle(true);

    static ClientSmtpSettings settings(int port) {
        return new ClientSmtpSettings("127.0.0.1", port, false, INFO, "secret", "KZ", "West-Med", true);
    }

    static ClientLetter letter(String id) {
        return new ClientLetter("West-Med", INFO, List.of("zz-client@clinic.kz"), "Re: Запрос цены", "Здравствуйте!",
                "С уважением,\nWest-Med", null, "<client-1@mail.ru>", id, List.of());
    }

    @Test
    void sendsLetter_messageIdAndThreadHeadersReachRecipient() throws Exception {
        ClientMailSender sender = new ClientMailSender(settings(ServerSetupTest.SMTP.getPort()));
        String id = ClientLetterBuilder.messageId(INFO);

        sender.send(ClientLetterBuilder.build(sender.session(), letter(id), ORAL));

        MimeMessage[] got = greenMail.getReceivedMessages();
        assertThat(got).hasSize(1);
        assertThat(got[0].getMessageID()).isEqualTo(id);
        assertThat(got[0].getHeader("In-Reply-To", null)).isEqualTo("<client-1@mail.ru>");
        assertThat(got[0].getSubject()).isEqualTo("Re: Запрос цены");
        assertThat(((InternetAddress) got[0].getFrom()[0]).getPersonal()).isEqualTo("West-Med");
    }

    @Test
    void serverDown_failureTextSaysNotResponding() throws Exception {
        ClientMailSender sender = new ClientMailSender(settings(3999));   // на 3999 никто не слушает
        MimeMessage m = ClientLetterBuilder.build(sender.session(), letter(ClientLetterBuilder.messageId(INFO)), ORAL);
        Throwable e = catchThrowable(() -> sender.send(m));
        assertThat(e).isInstanceOf(MailException.class);
        assertThat(ClientMailSender.failureText(e)).isEqualTo(ClientMailSender.DOWN);
    }

    @Test
    void failureText_authAddressConnectUnknown_noForeignText() throws Exception {
        assertThat(ClientMailSender.failureText(new MailAuthenticationException("535 bad creds for info@westmed.kz")))
                .isEqualTo(ClientMailSender.AUTH);
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        SendFailedException bad = new SendFailedException("Invalid Addresses", null, new Address[0], new Address[0],
                new Address[]{new InternetAddress("x@clinic.kz")});
        assertThat(ClientMailSender.failureText(new MailSendException(Map.<Object, Exception>of(m, bad))))
                .isEqualTo(ClientMailSender.ADDRESS);
        assertThat(ClientMailSender.failureText(new MailSendException("Mail server connection failed",
                new MessagingException("Couldn't connect to host smtp.mail.ru", new ConnectException("refused")))))
                .isEqualTo(ClientMailSender.DOWN);
        assertThat(ClientMailSender.failureText(new IllegalStateException("info@westmed.kz")))
                .isEqualTo("Письмо не ушло (IllegalStateException)");
    }
}
