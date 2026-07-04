package com.vladoose.nir.integration.goszakup;

import java.util.function.Supplier;

/**
 * Ретрай транзиентных сбоев goszakup: площадка периодически моргает (timeout/5xx),
 * которые {@link GoszakupHttpClient} оборачивает в {@link IllegalStateException}.
 * НЕ ретраит {@link GoszakupNotFoundException} (404 — не транзиентно) и прочие исключения.
 */
final class GoszakupRetry {

    private GoszakupRetry() {}

    static <T> T withRetries(int attempts, long sleepMs, Supplier<T> call) {
        IllegalStateException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return call.get();
            } catch (IllegalStateException e) {
                last = e;
                if (i < attempts - 1 && sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }
}
