package com.vladoose.nir.integration.goszakup;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoszakupRetryTest {

    @Test
    void retriesTransientThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String r = GoszakupRetry.withRetries(3, 0, () -> {
            if (calls.incrementAndGet() < 3) throw new IllegalStateException("goszakup timeout");
            return "ok";
        });
        assertThat(r).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void rethrowsAfterExhaustingAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> GoszakupRetry.withRetries(3, 0, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("всегда падает");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("всегда падает");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void doesNotRetryOtherExceptions() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> GoszakupRetry.withRetries(3, 0, () -> {
            calls.incrementAndGet();
            throw new GoszakupNotFoundException("404 — не транзиентно");
        })).isInstanceOf(GoszakupNotFoundException.class);
        assertThat(calls.get()).isEqualTo(1); // не ретраим 404
    }
}
