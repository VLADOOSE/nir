package com.vladoose.nir.integration.telegram;

/**
 * Отказ Telegram или сети. Текст — свой, БЕЗ адреса запроса: токен бота стоит прямо в пути. status 0 — сеть или
 * настройки; retryAfterSeconds — пауза из ответа 429.
 */
public class TelegramException extends RuntimeException {

    private final int status;
    private final Integer retryAfterSeconds;

    public TelegramException(int status, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() { return status; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
}
