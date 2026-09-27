package com.vladoose.nir.integration.westmed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.westmed.dto.WestmedPage;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP к API сайта westmed.kz. Токен (живёт 30 мин) держим в памяти. На отказ admin-вызова —
 * ⚠️ и 401, и 403: протухший токен сайт отвечает 403 (у него нет entry point, Spring отдаёт анониму 403),
 * реагируй мы только на 401 — через полчаса приём заявок молча встал бы. Повторный вход — один.
 * Пароль и токен не попадают ни в логи, ни в тексты исключений.
 */
@Component
public class WestmedHttpClient implements WestmedClient {

    private static final TypeReference<WestmedPage<WestmedPriceRequest>> PRICE_PAGE = new TypeReference<>() {};
    private static final TypeReference<WestmedPage<WestmedQuoteRequest>> QUOTE_PAGE = new TypeReference<>() {};
    private static final TypeReference<WestmedPage<WestmedProduct>> PRODUCT_PAGE = new TypeReference<>() {};

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String username;
    private final String password;
    private volatile String token;

    public WestmedHttpClient(ObjectMapper objectMapper,
                             @Value("${leads.westmed.base-url:https://westmed.kz}") String baseUrl,
                             @Value("${leads.westmed.username:}") String username,
                             @Value("${leads.westmed.password:}") String password) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public boolean isConfigured() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    @Override
    public WestmedPage<WestmedPriceRequest> fetchPriceRequests(int page, int size) {
        return admin("GET", "/api/admin/requests?page=" + page + "&size=" + size, null, PRICE_PAGE);
    }

    @Override
    public WestmedPage<WestmedQuoteRequest> fetchQuoteRequests(int page, int size) {
        return admin("GET", "/api/admin/quote-requests?page=" + page + "&size=" + size, null, QUOTE_PAGE);
    }

    @Override
    public List<WestmedProduct> searchProducts(String text, int size) {
        String path = "/api/v1/products?search=" + URLEncoder.encode(text, StandardCharsets.UTF_8) + "&size=" + size;
        HttpResponse<String> r = send("GET", path, null, null);
        if (r.statusCode() != 200) {
            throw new WestmedApiException(r.statusCode(), "поиск товара на сайте: HTTP " + r.statusCode());
        }
        return parse(r.body(), PRODUCT_PAGE).contentOrEmpty();
    }

    @Override
    public void updateStatus(WestmedKind kind, String siteId, String status) {
        admin("PATCH", "/api/admin/" + kind.path() + "/" + siteId + "/status", json(Map.of("status", status)), null);
    }

    private <T> T admin(String method, String path, String body, TypeReference<T> type) {
        HttpResponse<String> r = send(method, path, body, currentToken());
        if (isTokenRejected(r)) {
            token = null;   // протух или отозван — входим заново ровно один раз
            r = send(method, path, body, currentToken());
            if (isTokenRejected(r)) {
                throw new WestmedAuthException(
                        "сайт отклоняет учётку АИС даже после повторного входа (HTTP " + r.statusCode() + ")");
            }
        }
        if (r.statusCode() / 100 != 2) {
            throw new WestmedApiException(r.statusCode(), "HTTP " + r.statusCode() + " на " + method + " " + stripQuery(path));
        }
        return type == null ? null : parse(r.body(), type);
    }

    private static boolean isTokenRejected(HttpResponse<String> r) {
        return r.statusCode() == 401 || r.statusCode() == 403;
    }

    private synchronized String currentToken() {
        if (token != null) return token;
        if (!isConfigured()) throw new WestmedAuthException("не заданы учётные данные сайта");
        HttpResponse<String> r = send("POST", "/api/auth/login", json(Map.of("email", username, "password", password)), null);
        if (r.statusCode() == 401) throw new WestmedAuthException("вход не удался: неверный логин или пароль");
        if (r.statusCode() == 429) {
            throw new WestmedAuthException("вход не удался: сайт временно ограничил попытки входа (HTTP 429)");
        }
        if (r.statusCode() != 200) throw new WestmedApiException(r.statusCode(), "вход на сайт: HTTP " + r.statusCode());
        String t;
        try {
            t = objectMapper.readTree(r.body()).path("accessToken").asText("");
        } catch (IOException e) {
            throw new WestmedApiException(r.statusCode(), "вход на сайт: ответ не разобран");
        }
        if (t.isBlank()) throw new WestmedAuthException("вход на сайт: токен не получен");
        token = t;
        return t;
    }

    private HttpResponse<String> send(String method, String path, String body, String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new WestmedApiException(0, "сайт недоступен: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " — " + e.getMessage() : ""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WestmedApiException(0, "запрос к сайту прерван");
        }
    }

    private <T> T parse(String body, TypeReference<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (IOException e) {
            throw new WestmedApiException(200, "ответ сайта не разобран: " + e.getClass().getSimpleName());
        }
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalStateException("не удалось собрать JSON запроса", e);
        }
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }
}
