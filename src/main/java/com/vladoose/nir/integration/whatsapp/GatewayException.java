package com.vladoose.nir.integration.whatsapp;

/**
 * Ошибка шлюза WhatsApp (Green-API, WAHA). status 0 — сеть/конфиг. Текст — человеческий и БЕЗ адреса и ключей:
 * он уходит в строку состояния, которую видит любой вошедший (у Green-API токен стоит прямо в пути URL).
 */
public class GatewayException extends RuntimeException {

    private final int status;

    public GatewayException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
