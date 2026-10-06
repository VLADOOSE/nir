package com.vladoose.nir.integration.http;

import com.vladoose.nir.util.ErrorText;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Обмен с внешней площадкой импорта с дедлайном на ВЕСЬ ответ: {@code HttpRequest.timeout} в JDK 17 снимается
 * после заголовков, и вставшее тело держало бы поток импорта вечно (CLAUDE.md §14). Тело — {@link LimitedBytes}
 * с обрывом на пределе внутри обмена. В отличие от whatsapp.GatewayHttp текст сбоя несёт ПРИЧИНУ
 * ({@code ErrorText.of}): у импорта токен только в заголовке, а «PKIX path building failed» — ровно то, по чему
 * нашли поломку СК-Фармации.
 */
public final class UpstreamHttp {

    private UpstreamHttp() {
    }

    /** HTTP/1.1: по умолчанию клиент JDK на http:// шлёт {@code Upgrade: h2c}, и часть серверов рвёт такой запрос. */
    public static HttpClient newClient(HttpClient.Redirect redirect) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(redirect)
                .build();
    }

    public static HttpResponse<byte[]> exchange(HttpClient http, HttpRequest req, long maxBytes, Duration deadline)
            throws UpstreamIoException {
        CompletableFuture<HttpResponse<byte[]>> f;
        try {
            f = http.sendAsync(req, info -> new LimitedBytes(maxBytes));
        } catch (RuntimeException e) {
            throw new UpstreamIoException(UpstreamIoException.Kind.IO, ErrorText.of(e), e);
        }
        try {
            return f.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new UpstreamIoException(UpstreamIoException.Kind.TIMEOUT,
                    "нет ответа за " + deadline.toSeconds() + " с", e);
        } catch (ExecutionException e) {
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
                if (c instanceof FileTooLargeException) {
                    throw new UpstreamIoException(UpstreamIoException.Kind.TOO_LARGE,
                            "ответ больше " + (maxBytes / (1024 * 1024)) + " МБ", c);
                }
            }
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new UpstreamIoException(UpstreamIoException.Kind.IO, ErrorText.of(c), c);
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new UpstreamIoException(UpstreamIoException.Kind.INTERRUPTED, "прервано", e);
        }
    }
}
