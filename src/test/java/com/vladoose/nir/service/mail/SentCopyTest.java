package com.vladoose.nir.service.mail;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.service.EmailService;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Копия письма АИС — в «Отправленные» ящика отправки (IMAP APPEND): Mail.ru кладёт туда только письма своей веб-почты,
 * а письма почтовых программ — нет, программа дописывает копию сама. Настоящие SMTP и IMAP (GreenMail).
 */
class SentCopyTest {

    static final String ZAKUP = "zakup@westmed.kz";
    static final String INFO = "info@westmed.kz";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(ZAKUP, "secret").withUser(INFO, "secret"))
            .withPerMethodLifecycle(true);

    @BeforeEach
    void sentFolders() throws Exception {
        for (String user : List.of(ZAKUP, INFO)) {
            GreenMailUser u = greenMail.getManagers().getUserManager().getUser(user);
            greenMail.getManagers().getImapHostManager().createMailbox(u, "Sent");
        }
    }

    private static EmailService email(SentCopy copy) {
        return email(copy, ServerSetupTest.SMTP.getPort());
    }

    private static EmailService email(SentCopy copy, int smtpPort) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(smtpPort);
        sender.setUsername(ZAKUP);
        sender.setPassword("secret");
        sender.getJavaMailProperties().put("mail.smtp.auth", "true");
        EmailService s = new EmailService(sender, copy);
        ReflectionTestUtils.setField(s, "fromAddress", ZAKUP);
        ReflectionTestUtils.setField(s, "replyTo", "");
        return s;
    }

    /** Копия — сразу в вызывающем потоке (в работе — свой поток «mail-sent-copy»). */
    private static SentCopy copier(boolean enabled, String imapUser, int imapPort) {
        return new SentCopy(enabled, ZAKUP, "127.0.0.1", imapPort, imapUser, "secret", "imap", Runnable::run);
    }

    /** Исполнитель-накопитель: задачи копии ждут, пока тест их не запустит. */
    private static SentCopy deferred(List<Runnable> tasks) {
        return new SentCopy(true, ZAKUP, "127.0.0.1", ServerSetupTest.IMAP.getPort(), ZAKUP, "secret", "imap", tasks::add);
    }

    private static SentCopy copier() {
        return copier(true, ZAKUP, ServerSetupTest.IMAP.getPort());
    }

    /** Письма папки ящика: тема, Message-ID, «прочитано». */
    record Stored(String subject, String messageId, boolean seen) {}

    static List<Stored> folder(String user, String name) throws Exception {
        Store store = Session.getInstance(new Properties()).getStore("imap");
        store.connect("127.0.0.1", ServerSetupTest.IMAP.getPort(), user, "secret");
        try {
            Folder f = store.getFolder(name);
            if (!f.exists()) return null;
            f.open(Folder.READ_ONLY);
            List<Stored> out = new ArrayList<>();
            for (Message m : f.getMessages()) {
                out.add(new Stored(m.getSubject(), ((MimeMessage) m).getMessageID(), m.isSet(Flags.Flag.SEEN)));
            }
            f.close(false);
            return out;
        } finally {
            store.close();
        }
    }

    @Test
    void sentLetterLandsInSentFolder_sameMessageId_markedRead() throws Exception {
        email(copier()).sendEmail(List.of("supplier@axion.test"), "[КП-143] Запрос коммерческого предложения", "Текст", List.of());

        // getReceivedMessages…() GreenMail считает и копию в «Отправленных» (фильтр — по адресатам письма), поэтому
        // письмо поставщика — из его «Входящих»
        List<MimeMessage> received = inbox("supplier@axion.test");
        assertThat(received).hasSize(1);
        List<Stored> sent = folder(ZAKUP, "Sent");
        assertThat(sent).containsExactly(new Stored("[КП-143] Запрос коммерческого предложения",
                received.get(0).getMessageID(), true));
        assertThat(folder(ZAKUP, "INBOX")).isEmpty();   // копия — не во «Входящие»: их читает приём почты
    }

    @Test
    void severalRecipients_oneCopy() throws Exception {
        email(copier()).sendEmail(List.of("a@ema.test", "b@ema.test"), "[КП-7] Тема", "Текст", List.of());

        assertThat(folder(ZAKUP, "Sent")).hasSize(1);
    }

    /** Ящик приёма — другой, чем ящик отправки: класть копию некуда (чужие «Отправленные» не трогаем). */
    @Test
    void imapMailboxIsNotTheSender_noCopyAnywhere() throws Exception {
        email(copier(true, INFO, ServerSetupTest.IMAP.getPort())).sendEmail(List.of("x@ema.test"), "Тема", "Текст", List.of());

        assertThat(inbox("x@ema.test")).hasSize(1);
        assertThat(folder(ZAKUP, "Sent")).isEmpty();
        assertThat(folder(INFO, "Sent")).isEmpty();
    }

    @Test
    void switchedOff_noCopy() throws Exception {
        email(copier(false, ZAKUP, ServerSetupTest.IMAP.getPort())).sendEmail(List.of("x@ema.test"), "Тема", "Текст", List.of());

        assertThat(inbox("x@ema.test")).hasSize(1);
        assertThat(folder(ZAKUP, "Sent")).isEmpty();
    }

    /** Почта IMAP недоступна — письмо всё равно считается ушедшим: копия не роняет отправку. */
    @Test
    void imapUnreachable_letterStillSent() throws Exception {
        EmailService s = email(copier(true, ZAKUP, 3999));

        assertThatCode(() -> s.sendEmail(List.of("x@ema.test"), "Тема", "Текст", List.of())).doesNotThrowAnyException();
        assertThat(inbox("x@ema.test")).hasSize(1);
    }

    /** Папки «Отправленные» нет — письмо ушло, папку АИС не заводит (у Mail.ru она своя, с признаком \Sent). */
    @Test
    void noSentFolder_letterStillSent_folderNotCreated() throws Exception {
        Store store = Session.getInstance(new Properties()).getStore("imap");
        store.connect("127.0.0.1", ServerSetupTest.IMAP.getPort(), ZAKUP, "secret");
        store.getFolder("Sent").delete(true);
        store.close();

        EmailService s = email(copier());
        assertThatCode(() -> s.sendEmail(List.of("x@ema.test"), "Тема", "Текст", List.of())).doesNotThrowAnyException();

        assertThat(inbox("x@ema.test")).hasSize(1);
        assertThat(folder(ZAKUP, "Sent")).isNull();
        assertThat(Arrays.stream(listFolders(ZAKUP))).containsExactly("INBOX");
    }

    /** «Входящие» адресата: GreenMail заводит его сам при доставке. */
    static List<MimeMessage> inbox(String email) throws Exception {
        GreenMailUser u = greenMail.getManagers().getUserManager().getUserByEmail(email);
        if (u == null) return List.of();
        List<MimeMessage> out = new ArrayList<>();
        for (var m : greenMail.getManagers().getImapHostManager().getInbox(u).getMessages()) out.add(m.getMimeMessage());
        return out;
    }

    static String[] listFolders(String user) throws Exception {
        Store store = Session.getInstance(new Properties()).getStore("imap");
        store.connect("127.0.0.1", ServerSetupTest.IMAP.getPort(), user, "secret");
        try {
            return Arrays.stream(store.getDefaultFolder().list("*")).map(Folder::getFullName).toArray(String[]::new);
        } finally {
            store.close();
        }
    }

    /** Письмо не ушло (SMTP недоступен) — копии нет: копируется только ушедшее. */
    @Test
    void letterNotSent_noCopy() throws Exception {
        EmailService s = email(copier(), 3998);

        assertThatThrownBy(() -> s.sendEmail(List.of("x@ema.test"), "Тема", "Текст", List.of())).isInstanceOf(RuntimeException.class);
        assertThat(folder(ZAKUP, "Sent")).isEmpty();
    }

    /** Копия пишется не в потоке отправки: зависший IMAP не держит окно «Запросить цены». */
    @Test
    void copyIsWrittenOffTheSendingThread() throws Exception {
        List<Runnable> tasks = new ArrayList<>();
        email(deferred(tasks)).sendEmail(List.of("x@ema.test"), "[КП-1] Тема", "Текст", List.of());

        assertThat(folder(ZAKUP, "Sent")).isEmpty();
        assertThat(tasks).isNotEmpty();
        tasks.forEach(Runnable::run);
        assertThat(folder(ZAKUP, "Sent")).extracting(Stored::subject).containsExactly("[КП-1] Тема");
    }

    /** Письма, накопившиеся до записи копий (запрос 20 поставщикам), уходят одним входом в ящик — и без повторов. */
    @Test
    void queuedCopiesGoInOneSession_noDuplicates() throws Exception {
        List<Runnable> tasks = new ArrayList<>();
        EmailService s = email(deferred(tasks));
        for (int i = 1; i <= 3; i++) s.sendEmail(List.of("x@ema.test"), "[КП-" + i + "] Тема", "Текст", List.of());

        tasks.get(0).run();
        assertThat(folder(ZAKUP, "Sent")).hasSize(3);
        tasks.subList(1, tasks.size()).forEach(Runnable::run);
        assertThat(folder(ZAKUP, "Sent")).extracting(Stored::subject)
                .containsExactlyInAnyOrder("[КП-1] Тема", "[КП-2] Тема", "[КП-3] Тема");
    }

    /** Свой Error (как OOM при снятии байтов) — не наружу: письмо уже ушло, исход отправки должен записаться. */
    static final class Boom extends Error {}

    @Test
    void errorWhileCapturingCopy_doesNotEscape() {
        MimeMessage broken = new MimeMessage((Session) null) {
            @Override
            public void writeTo(java.io.OutputStream os) {
                throw new Boom();
            }
        };

        assertThatCode(() -> copier().store(broken)).doesNotThrowAnyException();
    }
}
