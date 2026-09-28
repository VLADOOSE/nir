package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * HTTP к Green-API (спека whatsapp-chats §2, §10). ⚠️ Токен стоит В ПУТИ URL
 * ({apiUrl}/waInstance{id}/{метод}/{token}), поэтому URL не попадает ни в лог, ни в текст исключения:
 * сообщения об ошибках собираются вручную, из названия операции.
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

    public GreenApiHttpClient(ObjectMapper objectMapper,
                              @Value("${chats.whatsapp.api-url:}") String apiUrl,
                              @Value("${chats.whatsapp.id-instance:}") String idInstance,
                              @Value("${chats.whatsapp.api-token:}") String token) {
        this.objectMapper = objectMapper;
        String u = apiUrl == null ? "" : apiUrl.trim();
        this.apiUrl = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        this.idInstance = idInstance == null ? "" : idInstance.trim();
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public boolean isConfigured() {
        return !apiUrl.isEmpty() && !idInstance.isEmpty() && !token.isEmpty();
    }

    @Override
    public GreenApiReceived receive(int receiveTimeoutSec) {
        String what = "приёме сообщений";
        HttpResponse<String> r = call("GET", "receiveNotification", "?receiveTimeout=" + receiveTimeoutSec,
                Duration.ofSeconds(receiveTimeoutSec + 15L), what);
        JsonNode root = parse(r.body(), what);
        if (root == null || root.isNull() || !root.has("receiptId")) return null;
        return new GreenApiReceived(root.get("receiptId").asLong(), root.path("body"));
    }

    @Override
    public void delete(long receiptId) {
        call("DELETE", "deleteNotification", "/" + receiptId, Duration.ofSeconds(20), "удалении уведомления из очереди");
    }

    @Override
    public String state() {
        String what = "запросе состояния";
        JsonNode s = parse(call("GET", "getStateInstance", "", Duration.ofSeconds(20), what).body(), what);
        return s == null ? "" : s.path("stateInstance").asText("");
    }

    @Override
    public GreenApiSettings settings() {
        String what = "запросе настроек";
        JsonNode s = parse(call("GET", "getSettings", "", Duration.ofSeconds(20), what).body(), what);
        if (s == null) s = objectMapper.createObjectNode();
        return new GreenApiSettings(s.path("wid").asText(""), s.path("webhookUrl").asText(""),
                yes(s.path("incomingWebhook")), yes(s.path("outgoingMessageWebhook")));
    }

    @Override
    public byte[] download(String url, long maxBytes) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new GreenApiException(0, "Green-API: некорректная ссылка на файл");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        // https всегда; http — только если сам сервис на http (dev-стаб, тесты)
        if (!scheme.equals("https") && !scheme.equals(apiScheme())) {
            throw new GreenApiException(0, "Green-API: ссылка на файл не по https — не скачиваем");
        }
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build();
        try {
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() / 100 != 2) {
                    throw new GreenApiException(r.statusCode(), "Green-API: HTTP " + r.statusCode() + " при скачивании файла");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) throw new FileTooLargeException(maxBytes);
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } catch (IOException e) {
            throw new GreenApiException(0, "Green-API: файл не скачался: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GreenApiException(0, "Green-API: скачивание прервано");
        }
    }

    private HttpResponse<String> call(String method, String apiMethod, String suffix, Duration timeout, String what) {
        if (!isConfigured()) {
            throw new GreenApiAuthException(0, "не заданы учётные данные Green-API");
        }
        URI uri = URI.create(apiUrl + "/waInstance" + idInstance + "/" + apiMethod + "/" + token + suffix);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // только класс исключения: текст части исключений JDK содержит адрес
            throw new GreenApiException(0, "Green-API недоступен при " + what + ": " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GreenApiException(0, "Green-API: запрос прерван при " + what);
        }
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
}
