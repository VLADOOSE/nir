package com.vladoose.nir.integration.http;

/** Сбой обмена с площадкой, который знает, имеет ли смысл повтор (обрыв, 5xx, 429 — да; 4xx, разбор — нет). */
public interface RetryableFailure {
    boolean retryable();
}
