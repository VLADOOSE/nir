package com.vladoose.nir.service.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.vladoose.nir.service.mail.ImapTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImapMailboxConnectorTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP).withPerMethodLifecycle(true);

    GreenMailUser user;

    @BeforeEach
    void user() { user = greenMail.setUser(USER, USER, PASS); }

    ImapMailboxConnector connector() {
        return new ImapMailboxConnector(HOST, PORT, USER, PASS, "imap", 5_000, 5_000);
    }

    @Test
    void readOnly_fetchDoesNotMarkSeen() throws Exception {
        user.deliver(TestMimes.plain("s@x.kz", "Непрочитанное", "Текст"));

        try (MailboxSession s = connector().open()) {
            List<Long> uids = s.uidsAfter(0, 10);
            ParsedMail p = s.fetch(uids.get(0));
            assertThat(p.subject()).isEqualTo("Непрочитанное");
            assertThat(p.text()).contains("Текст");
        }

        assertThat(seen("Непрочитанное")).isFalse();
    }

    /** Папка — EXAMINE, а не SELECT: SELECT забрал бы у людей отметку «новое» (\Recent), даже ничего не читая. */
    @Test
    void open_examineLeavesRecentForPeople() throws Exception {
        user.deliver(TestMimes.plain("s@x.kz", "Новое", "1"));

        try (MailboxSession s = connector().open()) {
            assertThat(s.fetch(s.uidsAfter(0, 10).get(0)).subject()).isEqualTo("Новое");
        }

        assertThat(recent("Новое")).isTrue();
    }

    @Test
    void uidsAfter_lastUid_returnsNothing_despiteImapStarQuirk() throws Exception {
        user.deliver(TestMimes.plain("s@x.kz", "Первое", "1"));
        user.deliver(TestMimes.plain("s@x.kz", "Второе", "2"));

        try (MailboxSession s = connector().open()) {
            List<Long> all = s.uidsAfter(0, 10);
            assertThat(all).hasSize(2).isSorted();
            assertThat(s.uidsAfter(all.get(1), 10)).isEmpty();
            assertThat(s.uidsAfter(all.get(0), 10)).containsExactly(all.get(1));
            assertThat(s.uidsAfter(0, 1)).containsExactly(all.get(0));
        }
    }

    @Test
    void maxUid_emptyIsZero_thenLastDelivered() throws Exception {
        try (MailboxSession s = connector().open()) {
            assertThat(s.maxUid()).isZero();
        }
        user.deliver(TestMimes.plain("s@x.kz", "Одно", "1"));
        try (MailboxSession s = connector().open()) {
            assertThat(s.maxUid()).isEqualTo(s.uidsAfter(0, 10).get(0));
        }
    }

    /** После удаления письма число писем (номер последнего) и UID последнего расходятся — курсору нужен UID. */
    @Test
    void maxUid_isLastUid_notMessageCount() throws Exception {
        user.deliver(TestMimes.plain("s@x.kz", "Удалённое", "1"));
        user.deliver(TestMimes.plain("s@x.kz", "Последнее", "2"));
        delete("Удалённое");

        try (MailboxSession s = connector().open()) {
            List<Long> uids = s.uidsAfter(0, 10);
            assertThat(uids).hasSize(1);
            assertThat(uids.get(0)).isGreaterThan(1L);         // писем одно, а UID последнего больше
            assertThat(s.maxUid()).isEqualTo(uids.get(0));
        }
    }

    @Test
    void uidValidity_stableBetweenSessions() throws Exception {
        long a, b;
        try (MailboxSession s = connector().open()) { a = s.uidValidity(); }
        try (MailboxSession s = connector().open()) { b = s.uidValidity(); }
        assertThat(a).isEqualTo(b).isPositive();
    }

    @Test
    void uidsReceivedSince_exactTimeInCode() throws Exception {
        appendWithReceivedDate("Старое", Instant.now().minus(Duration.ofHours(2)));
        user.deliver(TestMimes.plain("s@x.kz", "Свежее", "новое"));

        try (MailboxSession s = connector().open()) {
            List<Long> window = s.uidsReceivedSince(Instant.now().minus(Duration.ofMinutes(60)), 10);
            assertThat(window).hasSize(1);
            assertThat(s.fetch(window.get(0)).subject()).isEqualTo("Свежее");
        }
    }

    @Test
    void uidsReceivedSince_overflow_keepsMostRecent() throws Exception {
        user.deliver(TestMimes.plain("s@x.kz", "Первое", "1"));
        user.deliver(TestMimes.plain("s@x.kz", "Второе", "2"));
        user.deliver(TestMimes.plain("s@x.kz", "Третье", "3"));

        try (MailboxSession s = connector().open()) {
            List<Long> all = s.uidsAfter(0, 10);
            assertThat(s.uidsReceivedSince(Instant.now().minus(Duration.ofMinutes(60)), 2))
                    .containsExactly(all.get(1), all.get(2));
        }
    }

    @Test
    void fetchUnknownUid_null_envelopeReadsHeaders() throws Exception {
        user.deliver(TestMimes.plain("Иван <ivan@x.kz>", "Конверт", "1"));
        try (MailboxSession s = connector().open()) {
            assertThat(s.fetch(999_999)).isNull();
            long uid = s.uidsAfter(0, 10).get(0);
            BrokenMail b = s.envelope(uid, new IllegalStateException("x"));
            assertThat(b.from()).isEqualTo("Иван <ivan@x.kz>");
            assertThat(b.subject()).isEqualTo("Конверт");
            assertThat(b.errorClass()).isEqualTo("IllegalStateException");
        }
    }

    /**
     * Возврат, прочитанный по IMAP, сохраняет тему исходного письма — по её метке [КП-id] возврат привязывается к запросу
     * КП. Поток части message/rfc822 для этого не годится: GreenMail отдаёт по BODY[n] только ТЕЛО вложенного письма,
     * без заголовков (прямой разбор MIME в тестах разбора этого не видит).
     */
    @Test
    void bounceOverImap_keepsOriginalSubject() throws Exception {
        user.deliver(TestMimes.dsn("[КП-123] Запрос КП", "b@x.kz", "550 5.1.1 User unknown"));
        user.deliver(TestMimes.dsnHeadersOnly("[КП-124] Запрос КП"));

        try (MailboxSession s = connector().open()) {
            List<Long> uids = s.uidsAfter(0, 10);
            assertThat(s.fetch(uids.get(0)).bounce().originalSubject()).isEqualTo("[КП-123] Запрос КП");
            assertThat(s.fetch(uids.get(1)).bounce().originalSubject()).isEqualTo("[КП-124] Запрос КП");
        }
    }

    /**
     * Конверт письма, которое не разобралось, идёт в короткую строку «Входящих»: U+0000 в имени отправителя и теме
     * (RFC 2047) PostgreSQL не примет — конверт отдаёт поля без него.
     */
    @Test
    void envelope_stripsNulFromSenderAndSubject() throws Exception {
        MimeMessage m = TestMimes.plain("s@x.kz", "x", "1");
        m.setHeader("From", TestMimes.encodedWord("Иван\0 Петров") + " <ivan@x.kz>");
        m.setHeader("Subject", TestMimes.encodedWord("Тема\0 с нулём"));
        user.deliver(m);

        try (MailboxSession s = connector().open()) {
            BrokenMail b = s.envelope(s.uidsAfter(0, 10).get(0), new IllegalStateException("x"));
            assertThat(b.from()).isEqualTo("Иван Петров <ivan@x.kz>");
            assertThat(b.subject()).isEqualTo("Тема с нулём");
            assertThat(b.messageId()).isNotNull().doesNotContain("\0");
        }
    }

    @Test
    void isAlive_falseAfterClose() throws Exception {
        MailboxSession s = connector().open();
        assertThat(s.isAlive()).isTrue();
        s.close();
        assertThat(s.isAlive()).isFalse();
    }

    /**
     * Сервер оборвал связь посреди прохода: чтение письма падает, папка это замечает (isAlive — false), а конверт не
     * бросает — отдаёт пустые поля. По isAlive проход отличает обрыв (стоп, курсор на месте) от битого письма.
     */
    @Test
    void serverDrop_fetchThrows_notAlive_envelopeEmpty() throws Exception {
        user.deliver(TestMimes.plain("Иван <ivan@x.kz>", "До обрыва", "1"));
        try (MailboxSession s = connector().open()) {
            long uid = s.uidsAfter(0, 10).get(0);
            assertThat(s.isAlive()).isTrue();

            greenMail.stop();                                   // повторная остановка в afterEach ничего не делает

            assertThatThrownBy(() -> s.fetch(uid)).isInstanceOf(Exception.class);
            assertThat(s.isAlive()).isFalse();
            BrokenMail b = s.envelope(uid, new MessagingException("обрыв"));
            assertThat(b.uid()).isEqualTo(uid);
            assertThat(b.from()).isNull();
            assertThat(b.subject()).isNull();
            assertThat(b.messageId()).isNull();
            assertThat(b.receivedAt()).isNotNull();
            assertThat(b.errorClass()).isEqualTo("MessagingException");
        }
    }

    /**
     * Живая папка остаётся живой, даже если почта отклонит НОВЫЙ вход (здесь — пароль сменили посреди сессии; на Mail.ru —
     * лимит соединений или сбой авторизации). Проверка хранилища в Angus брала бы второе соединение со входом и при
     * отказе закрывала бы и папку: битое письмо приняли бы за обрыв связи, проход вставал бы на нём каждый раз.
     */
    @Test
    void isAlive_secondLoginRefused_folderStillServes() throws Exception {
        user.deliver(TestMimes.plain("s@x.kz", "Письмо", "1"));
        try (MailboxSession s = connector().open()) {
            long uid = s.uidsAfter(0, 10).get(0);
            greenMail.setUser(USER, USER, "пароль сменили");    // текущая сессия уже вошла, новый вход отклонят

            assertThat(s.isAlive()).isTrue();
            assertThat(s.fetch(uid).subject()).isEqualTo("Письмо");
        }
    }

    @Test
    void silentServer_openFailsByTimeout() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket ignored = silent.accept()) { Thread.sleep(10_000); } catch (Exception ignored) { }
            });
            t.setDaemon(true);
            t.start();
            ImapMailboxConnector c = new ImapMailboxConnector(HOST, silent.getLocalPort(), USER, PASS, "imap", 1_000, 1_000);
            long t0 = System.nanoTime();
            assertThatThrownBy(c::open).isInstanceOf(Exception.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
        }
    }
}
