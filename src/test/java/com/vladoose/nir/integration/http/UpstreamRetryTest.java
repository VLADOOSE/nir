package com.vladoose.nir.integration.http;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamRetryTest {

    static class Fail extends RuntimeException implements RetryableFailure {
        final boolean r;
        Fail(boolean r) { super("x"); this.r = r; }
        @Override public boolean retryable() { return r; }
    }

    final List<Long> slept = new ArrayList<>();
    final UpstreamRetry.Sleeper rec = slept::add;

    @Test
    void retryableFailure_retriedWithPauses_thenSucceeds() {
        AtomicInteger n = new AtomicInteger();
        String r = UpstreamRetry.call(rec, () -> {
            if (n.incrementAndGet() < 3) throw new Fail(true);
            return "ok";
        });
        assertThat(r).isEqualTo("ok");
        assertThat(n.get()).isEqualTo(3);
        assertThat(slept).containsExactly(1000L, 3000L);
    }

    @Test
    void nonRetryable_thrownImmediately() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> UpstreamRetry.call(rec, () -> { n.incrementAndGet(); throw new Fail(false); }))
                .isInstanceOf(Fail.class);
        assertThat(n.get()).isEqualTo(1);
        assertThat(slept).isEmpty();
    }

    @Test
    void plainRuntimeException_notRetried() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> UpstreamRetry.call(rec, () -> { n.incrementAndGet(); throw new IllegalStateException("x"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(n.get()).isEqualTo(1);
    }

    @Test
    void exhausted_lastFailureThrown() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> UpstreamRetry.call(rec, () -> { n.incrementAndGet(); throw new Fail(true); }))
                .isInstanceOf(Fail.class);
        assertThat(n.get()).isEqualTo(3);
        assertThat(slept).containsExactly(1000L, 3000L);
    }

    @Test
    void interruptedSleep_throwsAtOnce_flagRestored() {
        AtomicInteger n = new AtomicInteger();
        UpstreamRetry.Sleeper interrupted = ms -> { throw new InterruptedException(); };
        try {
            assertThatThrownBy(() -> UpstreamRetry.call(interrupted, () -> { n.incrementAndGet(); throw new Fail(true); }))
                    .isInstanceOf(Fail.class);
            assertThat(n.get()).isEqualTo(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void status_classification() {
        assertThat(UpstreamRetry.retryableStatus(503)).isTrue();
        assertThat(UpstreamRetry.retryableStatus(500)).isTrue();
        assertThat(UpstreamRetry.retryableStatus(429)).isTrue();
        assertThat(UpstreamRetry.retryableStatus(400)).isFalse();
        assertThat(UpstreamRetry.retryableStatus(403)).isFalse();
        assertThat(UpstreamRetry.retryableStatus(404)).isFalse();
    }
}
