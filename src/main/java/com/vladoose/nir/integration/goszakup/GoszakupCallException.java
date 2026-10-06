package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.http.RetryableFailure;

/**
 * Сбой вызова goszakup. Текст — для оператора (итог прогона, тост): «где: что», без адреса площадки и токена.
 * {@link #retryable()} — имеет ли смысл повтор: обрыв, таймаут, 5xx и 429 — да; 4xx, редирект, ошибка разбора
 * и неполный ответ — нет (повтор только жжёт лимит площадки). Наследует {@link IllegalStateException}, чтобы
 * прежние места обработки сбоев goszakup продолжали его ловить.
 */
public class GoszakupCallException extends IllegalStateException implements RetryableFailure {

    private final boolean retryable;

    public GoszakupCallException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    @Override
    public boolean retryable() { return retryable; }
}
