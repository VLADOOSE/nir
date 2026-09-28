package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HTTP к Green-API (спека whatsapp-chats §2, §10). ⚠️ Токен стоит В ПУТИ URL
 * ({apiUrl}/waInstance{id}/{метод}/{token}), поэтому URL не попадает ни в лог, ни в текст исключения:
 * сообщения об ошибках собираются вручную, из названия операции. Тексты исключений JDK наружу не выходят вовсе —
 * часть из них содержит адрес целиком (ревью 2026-09-28: опечатка в WHATSAPP_API_URL выводила токен на экран).
 * <p>
 * ⚠️ Таймаут HttpRequest в JDK 17 снимается, как только пришли заголовки: тело, вставшее посередине, держало бы
 * единственный поток приёма вечно. Поэтому каждый обмен идёт через sendAsync с дедлайном на ВЕСЬ ответ.
 */
@Component
public class GreenApiHttpClient implements GreenApiClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)   // NORMAL не идёт с https на http
            .build();
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String idInstance;
    private final String token;
    /** Скачивание файла целиком — не дольше; вызовы API — не дольше своего таймаута плюс запас. */
    private final Duration downloadDeadline;
    private final Duration slack;

    @Autowired
    public GreenApiHttpClient(ObjectMapper objectMapper,
                              @Value("${chats.whatsapp.api-url:}") String apiUrl,
                              @Value("${chats.whatsapp.id-instance:}") String idInstance,
                              @Value("${chats.whatsapp.api-token:}") String token) {
        this(objectMapper, apiUrl, idInstance, token, Duration.ofSeconds(60), Duration.ofSeconds(15));
    }

    /** Короткие дедлайны — для тестов «зависшего» ответа. */
    GreenApiHttpClient(ObjectMapper objectMapper, String apiUrl, String idInstance, String token,
                       Duration downloadDeadline, Duration slack) {
        this.objectMapper = objectMapper;
        String u = apiUrl == null ? "" : apiUrl.trim();
        this.apiUrl = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        this.idInstance = idInstance == null ? "" : idInstance.trim();
        this.token = token == null ? "" : token.trim();
        this.downloadDeadline = downloadDeadline;
        this.slack = slack;
    }

    @Override
    public boolean isConfigured() {
        return !apiUrl.isEmpty() && !idInstance.isEmpty() && !token.isEmpty();
    }

    @Override
    public GreenApiReceived receive(int receiveTimeoutSec) {
        String what = "приёме сообщений";
        HttpResponse<String> r = call("GET", "receiveNotification", "?receiveTimeout=" + receiveTimeoutSec,
                Duration.ofSeconds(receiveTimeoutSec).plus(slack), what);
        JsonNode root = parse(r.body(), what);
        if (root == null || root.isNull() || !root.has("receiptId")) return null;
        return new GreenApiReceived(root.get("receiptId").asLong(), root.path("body"));
    }

    @Override
    public void delete(long receiptId) {
        call("DELETE", "deleteNotification", "/" + receiptId, slack.plusSeconds(5), "удалении уведомления из очереди");
    }

    @Override
    public String state() {
        String what = "запросе состояния";
        JsonNode s = parse(call("GET", "getStateInstance", "", slack.plusSeconds(5), what).body(), what);
        return s == null ? "" : s.path("stateInstance").asText("");
    }

    @Override
    public GreenApiSettings settings() {
        String what = "запросе настроек";
        JsonNode s = parse(call("GET", "getSettings", "", slack.plusSeconds(5), what).body(), what);
        if (s == null) s = objectMapper.createObjectNode();
        return new GreenApiSettings(s.path("wid").asText(""), s.path("webhookUrl").asText(""),
                yes(s.path("incomingWebhook")), yes(s.path("outgoingMessageWebhook")));
    }

    @Override
    public byte[] download(String url, long maxBytes) {
        String what = "скачивании файла";
        HttpRequest req;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            // https всегда; http — только если сам сервис на http (dev-стаб, тесты)
            if (!scheme.equals("https") && !scheme.equals(apiScheme())) {
                throw new GreenApiException(0, "Green-API: ссылка на файл не по https — не скачиваем");
            }
            req = HttpRequest.newBuilder(uri).timeout(downloadDeadline).GET().build();
        } catch (IllegalArgumentException e) {
            throw new GreenApiException(0, "Green-API: некорректная ссылка на файл");
        }
        HttpResponse<byte[]> r = exchange(req, info -> info.statusCode() / 100 == 2
                ? new LimitedBytes(maxBytes)
                : HttpResponse.BodySubscribers.replacing(null), downloadDeadline, what);
        if (r.statusCode() / 100 != 2) {
            throw new GreenApiException(r.statusCode(), "Green-API: HTTP " + r.statusCode() + " при скачивании файла");
        }
        return r.body();
    }

    private HttpResponse<String> call(String method, String apiMethod, String suffix, Duration deadline, String what) {
        if (!isConfigured()) {
            throw new GreenApiAuthException(0, "не заданы учётные данные Green-API");
        }
        HttpRequest req;
        try {
            URI uri = URI.create(apiUrl + "/waInstance" + idInstance + "/" + apiMethod + "/" + token + suffix);
            req = HttpRequest.newBuilder(uri).timeout(deadline).header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
        } catch (IllegalArgumentException e) {
            // текст исключения JDK содержит весь адрес вместе с токеном — наружу только своё
            throw new GreenApiAuthException(0, "Green-API: адрес запроса не собирается — проверьте WHATSAPP_API_URL, "
                    + "WHATSAPP_ID_INSTANCE и WHATSAPP_API_TOKEN (опечатка, пробел или кавычка)");
        }
        HttpResponse<String> r = exchange(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), deadline, what);
        int st = r.statusCode();
        if (st == 401 || st == 403) {
            throw new GreenApiAuthException(st, "Green-API отклонил ключ (HTTP " + st + ") при " + what
                    + " — проверьте idInstance и токен");
        }
        if (st == 466) {
            throw new GreenApiQuotaException(st, "Green-API: исчерпан лимит тарифа (HTTP 466) при " + what);
        }
        if (st / 100 != 2) {
            throw new GreenApiException(st, "Green-API: HTTP " + st + " при " + what);
        }
        return r;
    }

    /** Обмен целиком (заголовки И тело) — не дольше deadline; висящий запрос отменяется. */
    private <T> HttpResponse<T> exchange(HttpRequest req, HttpResponse.BodyHandler<T> handler, Duration deadline, String what) {
        CompletableFuture<HttpResponse<T>> f;
        try {
            f = http.sendAsync(req, handler);
        } catch (RuntimeException e) {
            throw new GreenApiException(0, "Green-API: запрос не отправлен при " + what + ": " + e.getClass().getSimpleName());
        }
        try {
            return f.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new GreenApiException(0, "Green-API не ответил за " + deadline.toSeconds() + " с при " + what);
        } catch (ExecutionException e) {
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
                if (c instanceof FileTooLargeException tooLarge) throw tooLarge;
            }
            // только класс исключения: текст части исключений JDK содержит адрес
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new GreenApiException(0, "Green-API недоступен при " + what + ": " + c.getClass().getSimpleName());
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new GreenApiException(0, "Green-API: запрос прерван при " + what);
        }
    }

    private JsonNode parse(String body, String what) {
        if (body == null || body.isBlank()) return null;
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new GreenApiException(200, "Green-API: ответ не разобран при " + what);
        }
    }

    private String apiScheme() {
        try {
            String s = URI.create(apiUrl).getScheme();
            return s == null ? "" : s.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean yes(JsonNode n) {
        return "yes".equalsIgnoreCase(n.asText(""));
    }

    /** Байты тела с обрывом на пределе — внутри обмена, чтобы дедлайн покрывал и чтение тела. */
    private static final class LimitedBytes implements HttpResponse.BodySubscriber<byte[]> {
        private final long maxBytes;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private long total;

        LimitedBytes(long maxBytes) { this.maxBytes = maxBytes; }

        @Override
        public CompletionStage<byte[]> getBody() { return result; }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return;
            for (ByteBuffer b : items) {
                total += b.remaining();
                if (total > maxBytes) {
                    subscription.cancel();
                    result.completeExceptionally(new FileTooLargeException(maxBytes));
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                out.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable t) { result.completeExceptionally(t); }

        @Override
        public void onComplete() { result.complete(out.toByteArray()); }
    }
}
