package com.vladoose.nir.integration.http;

/**
 * Сбой транспорта при обмене с площадкой импорта. Текст — для человека: попадает в итог прогона и тост.
 * Повторяемы только {@link Kind#TIMEOUT} и {@link Kind#IO}: ответ сверх предела и прерывание потока
 * повтором не лечатся.
 */
public class UpstreamIoException extends Exception implements RetryableFailure {

    public enum Kind { TIMEOUT, IO, TOO_LARGE, INTERRUPTED }

    private final Kind kind;

    public UpstreamIoException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() { return kind; }

    @Override
    public boolean retryable() { return kind == Kind.TIMEOUT || kind == Kind.IO; }
}
