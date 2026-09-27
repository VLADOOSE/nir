package com.vladoose.nir.exception;

/** 429: сработал лимит (например, слишком много ожидающих запросов доступа). */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
