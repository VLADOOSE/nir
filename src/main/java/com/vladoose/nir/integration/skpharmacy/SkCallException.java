package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.http.RetryableFailure;

/**
 * Сбой вызова fms.ecc.kz. Текст — для оператора («где: что», без хоста в пути). {@link #retryable()} — имеет ли
 * смысл повтор: обрыв, таймаут, 5xx и 429 — да; 4xx, предел тела, прерывание — нет. Наследует
 * {@link UpstreamException}, поэтому кнопка «ТЗ» по-прежнему отвечает 502.
 */
public class SkCallException extends UpstreamException implements RetryableFailure {

    private final boolean retryable;

    public SkCallException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public SkCallException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    @Override
    public boolean retryable() { return retryable; }
}
