package com.vladoose.nir.integration.greenapi;

/** Ошибка Green-API. status 0 — сеть/конфиг. Текст — человеческий и БЕЗ URL: в пути URL стоит токен. */
public class GreenApiException extends RuntimeException {

    private final int status;

    public GreenApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
