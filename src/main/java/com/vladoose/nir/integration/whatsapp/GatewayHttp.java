package com.vladoose.nir.integration.whatsapp;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HTTP-обмен со шлюзом WhatsApp с дедлайном на ВЕСЬ ответ (заголовки И тело): таймаут HttpRequest в JDK 17 снимается,
 * как только пришли заголовки, и тело, вставшее посередине, держало бы единственный поток приёма вечно. Тексты ошибок —
 * только название операции и класс исключения: текст части исключений JDK содержит адрес (у Green-API в нём токен).
 */
public final class GatewayHttp {

    private GatewayHttp() {}

    public static <T> HttpResponse<T> exchange(HttpClient http, HttpRequest req, HttpResponse.BodyHandler<T> handler,
                                               Duration deadline, String gateway, String what) {
        CompletableFuture<HttpResponse<T>> f;
        try {
            f = http.sendAsync(req, handler);
        } catch (RuntimeException e) {
            throw new GatewayException(0, gateway + ": запрос не отправлен при " + what + ": " + e.getClass().getSimpleName());
        }
        try {
            return f.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new GatewayException(0, gateway + " не ответил за " + deadline.toSeconds() + " с при " + what);
        } catch (ExecutionException e) {
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
                if (c instanceof FileTooLargeException tooLarge) throw tooLarge;
            }
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new GatewayException(0, gateway + " недоступен при " + what + ": " + c.getClass().getSimpleName());
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new GatewayException(0, gateway + ": запрос прерван при " + what);
        }
    }
}
