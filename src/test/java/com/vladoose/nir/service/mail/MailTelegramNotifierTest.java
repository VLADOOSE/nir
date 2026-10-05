package com.vladoose.nir.service.mail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.telegram.TelegramClient;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.integration.telegram.TelegramStubServer;
import com.vladoose.nir.repository.InboundEmailRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MailTelegramNotifierTest {

    static final String TOKEN = "123456:SECRET-TOKEN";

    @Autowired InboundEmailRepository inboundRepo;
    @Autowired MailNotifyStore store;
    @Autowired EntityManager em;

    TelegramStubServer stub;
    MutableClock clock;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-05T09:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void up() throws Exception {
        MarketContext.set(Market.KZ);
        // чужие PENDING (живые проверки на этой базе) не должны попасть в пачку теста — откатится вместе с тестом
        em.createNativeQuery("update inbound_email set notify_status = 'SENT' where notify_status = 'PENDING'").executeUpdate();
        stub = TelegramStubServer.start(0);
        clock = new MutableClock();
    }

    @AfterEach
    void down() {
        stub.close();
        MarketContext.clear();
    }

    MailTelegramNotifier notifier(boolean enabled) {
        TelegramSettings s = new TelegramSettings(enabled, stub.url(), TOKEN, "-1001", "77");
        return new MailTelegramNotifier(store, new TelegramClient(s, new ObjectMapper()), s, clock);
    }

    InboundEmail pending(String text, boolean silent, Instant queuedAt) {
        return inboundRepo.save(InboundEmail.builder().fromAddress("a@x.kz").subject("S").type(InboundType.UNMATCHED)
                .status(InboundStatus.NEW).mailbox("zz@test.kz").notifyStatus(NotifyStatus.PENDING)
                .notifyText(text).notifySilent(silent).notifyQueuedAt(queuedAt.atOffset(ZoneOffset.UTC)).build());
    }

    InboundEmail reload(InboundEmail e) {
        em.flush();
        em.clear();
        return inboundRepo.findById(e.getId()).orElseThrow();
    }

    @Test
    void sendsPendingInIdOrder_marksSent() {
        InboundEmail a = pending("первое", true, clock.now);
        InboundEmail b = pending("второе", false, clock.now);

        MailTelegramNotifier.FlushResult r = notifier(true).flush();

        assertThat(r.sent()).isEqualTo(2);
        assertThat(r.pending()).isZero();
        assertThat(stub.requests()).hasSize(2);
        assertThat(stub.requests().get(0).body()).contains("первое").contains("\"disable_notification\":true");
        assertThat(stub.requests().get(1).body()).contains("второе").doesNotContain("disable_notification");
        assertThat(reload(a).getNotifyStatus()).isEqualTo(NotifyStatus.SENT);
        assertThat(reload(b).getNotifiedAt()).isNotNull();
    }

    @Test
    void batchOf20_restGoesNextPass() {
        for (int i = 1; i <= 21; i++) pending("уведомление " + i, true, clock.now);

        MailTelegramNotifier.FlushResult r = notifier(true).flush();

        assertThat(r.sent()).isEqualTo(20);
        assertThat(r.pending()).isEqualTo(1);
        assertThat(stub.requests()).hasSize(20);
        assertThat(notifier(true).flush().sent()).isEqualTo(1);
        assertThat(stub.requests().get(20).body()).contains("уведомление 21");
    }

    @Test
    void serverError_stopsBatch_countsAttempt() {
        InboundEmail a = pending("первое", false, clock.now);
        InboundEmail b = pending("второе", false, clock.now);
        stub.enqueue(TelegramStubServer.Reply.error(500, "boom"));

        MailTelegramNotifier.FlushResult r = notifier(true).flush();

        assertThat(r.sent()).isZero();
        assertThat(r.pending()).isEqualTo(2);
        assertThat(r.lastError()).contains("HTTP 500");
        assertThat(stub.requests()).hasSize(1);
        InboundEmail ra = reload(a);
        assertThat(ra.getNotifyAttempts()).isEqualTo(1);
        assertThat(ra.getNotifyError()).contains("HTTP 500");
        assertThat(reload(b).getNotifyAttempts()).isZero();
    }

    @Test
    void rateLimit_pausesUntilRetryAfter() {
        pending("первое", false, clock.now);
        stub.enqueue(TelegramStubServer.Reply.tooMany(30));
        MailTelegramNotifier n = notifier(true);

        n.flush();
        n.flush();                                     // та же секунда — пауза, запросов нет
        assertThat(stub.requests()).hasSize(1);

        clock.now = clock.now.plusSeconds(31);
        MailTelegramNotifier.FlushResult r = n.flush();
        assertThat(r.sent()).isEqualTo(1);
        assertThat(stub.requests()).hasSize(2);
    }

    @Test
    void rateLimit_pauseCountsFromResponse_notFromBatchStart() {
        // каждый запрос к Telegram «идёт» 10 с: на часах — clock.now + 10 с × число запросов к заглушке
        Clock ticking = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return clock.now.plusSeconds(10L * stub.requests().size()); }
        };
        Instant start = clock.now;
        pending("первое", false, start);
        pending("второе", false, start);
        stub.enqueue(TelegramStubServer.Reply.ok(1), TelegramStubServer.Reply.tooMany(30));
        TelegramSettings s = new TelegramSettings(true, stub.url(), TOKEN, "-1001", "77");
        MailTelegramNotifier n = new MailTelegramNotifier(store, new TelegramClient(s, new ObjectMapper()), s, ticking);

        assertThat(n.flush().sent()).isEqualTo(1);     // 429 пришёл на 20-й секунде — пауза до 50-й
        clock.now = start.plusSeconds(25);             // на часах 45-я: от начала пачки 30 с прошли, от ответа — нет
        n.flush();
        assertThat(stub.requests()).hasSize(2);

        clock.now = start.plusSeconds(31);             // 51-я — пауза кончилась
        assertThat(n.flush().sent()).isEqualTo(1);
        assertThat(stub.requests()).hasSize(3);
    }

    @Test
    void olderThanDay_failedWithoutRequest() {
        InboundEmail old = pending("старое", false, clock.now.minus(Duration.ofHours(25)));

        notifier(true).flush();

        assertThat(stub.requests()).isEmpty();
        assertThat(reload(old).getNotifyStatus()).isEqualTo(NotifyStatus.FAILED);
    }

    @Test
    void olderThanDay_freshBehindItStillGoesInSamePass() {
        InboundEmail old = pending("старое", false, clock.now.minus(Duration.ofHours(25)));
        InboundEmail fresh = pending("свежее", false, clock.now);

        MailTelegramNotifier.FlushResult r = notifier(true).flush();

        assertThat(r.sent()).isEqualTo(1);
        assertThat(stub.requests()).hasSize(1);
        assertThat(stub.requests().get(0).body()).contains("свежее");
        InboundEmail ro = reload(old);
        assertThat(ro.getNotifyStatus()).isEqualTo(NotifyStatus.FAILED);
        assertThat(ro.getNotifyError()).startsWith("не отправлено за сутки");
        assertThat(reload(fresh).getNotifyStatus()).isEqualTo(NotifyStatus.SENT);
    }

    @Test
    void unexpectedFailure_classOnly_noText_stopsBatch() {
        Logger logger = (Logger) LoggerFactory.getLogger(MailTelegramNotifier.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            InboundEmail a = pending("первое", false, clock.now);
            InboundEmail b = pending("второе", false, clock.now);
            TelegramSettings s = new TelegramSettings(true, stub.url(), TOKEN, "-1001", "77");
            TelegramClient defective = new TelegramClient(s, new ObjectMapper()) {
                @Override public long sendMail(String text, boolean silent) {   // дефект у нас: адрес с токеном в тексте
                    throw new IllegalStateException("сбой на " + stub.url() + "/bot" + TOKEN + "/sendMessage");
                }
            };

            MailTelegramNotifier.FlushResult r = new MailTelegramNotifier(store, defective, s, clock).flush();

            assertThat(r.sent()).isZero();
            assertThat(r.pending()).isEqualTo(2);
            assertThat(r.lastError()).isEqualTo("внутренняя ошибка (IllegalStateException)");
            InboundEmail ra = reload(a);
            assertThat(ra.getNotifyAttempts()).isEqualTo(1);
            assertThat(ra.getNotifyError()).isEqualTo("внутренняя ошибка (IllegalStateException)");
            assertThat(reload(b).getNotifyAttempts()).isZero();
            assertThat(logs.list).isNotEmpty();
            assertThat(logs.list).allSatisfy(ev -> assertThat(ev.getFormattedMessage()).doesNotContain("SECRET"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void notConfigured_doesNothing() {
        InboundEmail a = pending("x", false, clock.now);
        notifier(false).flush();
        assertThat(stub.requests()).isEmpty();
        assertThat(reload(a).getNotifyStatus()).isEqualTo(NotifyStatus.PENDING);
    }

    @Test
    void tokenNeverInLogsOrStoredError() {
        Logger logger = (Logger) LoggerFactory.getLogger(MailTelegramNotifier.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            InboundEmail a = pending("x", false, clock.now);
            stub.enqueue(TelegramStubServer.Reply.error(400, "Bad Request: message thread not found"));
            notifier(true).flush();

            assertThat(reload(a).getNotifyError()).contains("message thread not found").doesNotContain("SECRET");
            int port = stub.port();
            stub.close();                               // обрыв соединения — второй вид отказа
            TelegramSettings s = new TelegramSettings(true, "http://127.0.0.1:" + port, TOKEN, "-1001", "77");
            new MailTelegramNotifier(store, new TelegramClient(s, new ObjectMapper()), s, clock).flush();

            assertThat(logs.list).isNotEmpty();
            assertThat(logs.list).allSatisfy(ev -> assertThat(ev.getFormattedMessage()).doesNotContain("SECRET"));
        } finally {
            logger.detachAppender(logs);
        }
    }
}
