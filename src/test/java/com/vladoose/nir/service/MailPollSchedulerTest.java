package com.vladoose.nir.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MailPollSchedulerTest {

    MailReceiveService service = mock(MailReceiveService.class);
    /** Логи планировщика — в список, а не в вывод сборки: тесты нарочно роняют проход, WARN со стеком там ожидаемы. */
    Logger logger = (Logger) LoggerFactory.getLogger(MailPollScheduler.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
        logger.setAdditive(false);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logger.setAdditive(true);
    }

    PollResultResponse result(String msg) {
        PollResultResponse r = new PollResultResponse();
        r.setEnabled(true);
        r.setMessage(msg);
        return r;
    }

    @Test
    void tick_disabled_noPass() throws Exception {
        new MailPollScheduler(service, false).tick();
        Thread.sleep(200);
        verify(service, never()).poll();
    }

    @Test
    void tick_runsOnOwnThread_withMailboxMarket() throws Exception {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        AtomicReference<String> thread = new AtomicReference<>();
        AtomicReference<Market> market = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        when(service.poll()).thenAnswer(inv -> {
            thread.set(Thread.currentThread().getName());
            market.set(MarketContext.get());
            done.countDown();
            return result("ok");
        });

        new MailPollScheduler(service, true).tick();

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(thread.get()).isEqualTo("mail-imap");
        assertThat(market.get()).isEqualTo(Market.KZ);
    }

    @Test
    void tick_skipsWhilePassRunning() throws Exception {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        CountDownLatch release = new CountDownLatch(1);
        when(service.poll()).thenAnswer(inv -> { release.await(2, TimeUnit.SECONDS); return result("ok"); });
        MailPollScheduler s = new MailPollScheduler(service, true);

        s.tick();
        s.tick();
        s.tick();
        release.countDown();

        verify(service, timeout(2000).times(1)).poll();
        Thread.sleep(200);
        verify(service, times(1)).poll();
    }

    @Test
    void run_isSerializedWithTickPass() throws Exception {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        when(service.poll()).thenAnswer(inv -> {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            firstStarted.countDown();
            Thread.sleep(300);
            active.decrementAndGet();
            return result("ok");
        });
        MailPollScheduler s = new MailPollScheduler(service, true);

        s.tick();
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        PollResultResponse r = s.run();

        assertThat(r.getMessage()).isEqualTo("ok");
        assertThat(maxActive.get()).isEqualTo(1);
        verify(service, times(2)).poll();
    }

    /** Не дождались — не ошибка и не успех: pending, страница покажет это нейтрально (R20). */
    @Test
    void run_timeout_returnsStillRunningMessage() {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        when(service.poll()).thenAnswer(inv -> { Thread.sleep(1_000); return result("поздно"); });

        PollResultResponse r = new MailPollScheduler(service, true, Duration.ofMillis(200)).run();

        assertThat(r.getMessage()).contains("ещё идёт");
        assertThat(r.isPending()).isTrue();
        assertThat(r.isOk()).isFalse();
    }

    @Test
    void run_passThrows_messageWithClass_nextRunWorks() {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        when(service.poll()).thenThrow(new IllegalStateException("boom")).thenReturn(result("ok"));
        MailPollScheduler s = new MailPollScheduler(service, true);

        PollResultResponse failed = s.run();
        assertThat(failed.getMessage()).contains("IllegalStateException");
        assertThat(failed.isOk()).isFalse();
        assertThat(failed.isPending()).isFalse();
        assertThat(s.run().getMessage()).isEqualTo("ok");
    }

    /** Error прохода (OutOfMemoryError на вложении) доходит до run() через Future: провал, а не «ещё идёт». */
    @Test
    void run_passError_notOk_notPending() {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        when(service.poll()).thenThrow(new PassError());

        PollResultResponse r = new MailPollScheduler(service, true).run();

        assertThat(r.getMessage()).isEqualTo("Ошибка проверки почты: PassError");
        assertThat(r.isOk()).isFalse();
        assertThat(r.isPending()).isFalse();
    }

    /** Ожидание прервано (поток запроса прерывают при остановке сервера): провал, флаг прерывания возвращён. */
    @Test
    void run_interrupted_notOk_notPending_keepsInterruptFlag() throws Exception {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        CountDownLatch release = new CountDownLatch(1);
        // проход держим, иначе он успел бы кончиться до get(), и get() вернул бы итог, не глядя на прерывание
        when(service.poll()).thenAnswer(inv -> { release.await(2, TimeUnit.SECONDS); return result("поздно"); });
        MailPollScheduler s = new MailPollScheduler(service, true);

        Thread.currentThread().interrupt();
        PollResultResponse r = s.run();
        boolean interruptKept = Thread.interrupted();           // и снять флаг — не задеть следующие тесты
        release.countDown();

        assertThat(interruptKept).isTrue();
        assertThat(r.getMessage()).isEqualTo("Проверка почты прервана");
        assertThat(r.isOk()).isFalse();
        assertThat(r.isPending()).isFalse();
    }

    /** Свой Error: ветка та же, что у OutOfMemoryError, но настоящий OOM уронил бы воркер gradle (CLAUDE.md §14). */
    static class PassError extends Error {
    }

    /** Флаг «проход идёт» снимается и после упавшего прохода — иначе приём молча встал бы навсегда. */
    @Test
    void tick_afterPassEnds_evenFailed_nextTickRunsAgain() throws Exception {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        when(service.poll()).thenThrow(new IllegalStateException("boom")).thenReturn(result("ok"));
        MailPollScheduler s = new MailPollScheduler(service, true);

        s.tick();
        verify(service, timeout(2000).times(1)).poll();
        Thread.sleep(200);                       // флаг снимается в finally сразу после прохода
        s.tick();

        verify(service, timeout(2000).times(2)).poll();
    }

    /** Поток «mail-imap» живёт вечно: рынок прошлого прохода не должен в нём оставаться (§6 CLAUDE.md). */
    @Test
    void pass_clearsMarketAfterward() {
        List<Market> atPassStart = new CopyOnWriteArrayList<>();
        when(service.getMailboxMarket()).thenAnswer(inv -> {
            atPassStart.add(MarketContext.get());
            return Market.KZ;
        });
        when(service.poll()).thenReturn(result("ok"));
        MailPollScheduler s = new MailPollScheduler(service, true);

        s.run();
        s.run();

        // на входе второго прохода рынок первого уже снят (без рынка MarketContext отдаёт RF)
        assertThat(atPassStart).containsExactly(Market.RF, Market.RF);
    }
}
