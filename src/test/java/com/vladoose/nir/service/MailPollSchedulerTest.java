package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.Test;

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

    @Test
    void run_timeout_returnsStillRunningMessage() {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        when(service.poll()).thenAnswer(inv -> { Thread.sleep(1_000); return result("поздно"); });

        PollResultResponse r = new MailPollScheduler(service, true, Duration.ofMillis(200)).run();

        assertThat(r.getMessage()).contains("ещё идёт");
    }

    @Test
    void run_passThrows_messageWithClass_nextRunWorks() {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        when(service.poll()).thenThrow(new IllegalStateException("boom")).thenReturn(result("ok"));
        MailPollScheduler s = new MailPollScheduler(service, true);

        assertThat(s.run().getMessage()).contains("IllegalStateException");
        assertThat(s.run().getMessage()).isEqualTo("ok");
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
