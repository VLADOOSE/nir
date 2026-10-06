package com.vladoose.nir.integration.http;

import java.util.function.Supplier;

/**
 * Повтор вызова площадки: 3 попытки с паузами 1 и 3 с. Повторяется только {@link RuntimeException},
 * реализующее {@link RetryableFailure} с {@code retryable() == true} — 400/403 и ошибки разбора повтором
 * не лечатся, а лишние запросы жгут лимит площадки. Прерванный сон — сразу последнее исключение,
 * флаг прерывания восстановлен.
 */
public final class UpstreamRetry {

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    public static final Sleeper REAL = Thread::sleep;

    private static final long[] PAUSES_MS = {1000, 3000};
    private static final int ATTEMPTS = PAUSES_MS.length + 1;

    private UpstreamRetry() {
    }

    public static <T> T call(Sleeper sleeper, Supplier<T> call) {
        for (int i = 0; ; i++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                if (!(e instanceof RetryableFailure r) || !r.retryable() || i == ATTEMPTS - 1) throw e;
                try {
                    sleeper.sleep(PAUSES_MS[i]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** 5xx и 429 — сбой на стороне площадки, имеет смысл повторить; прочие коды — нет. */
    public static boolean retryableStatus(int status) {
        return status >= 500 || status == 429;
    }
}
