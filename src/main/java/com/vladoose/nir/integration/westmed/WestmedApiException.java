package com.vladoose.nir.integration.westmed;

/** Ошибка вызова сайта; status 0 — до ответа не дошло (сеть, таймаут). */
public class WestmedApiException extends RuntimeException {

    private final int status;

    public WestmedApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
