package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.integration.whatsapp.GatewayAuthException;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.GatewayHttp;
import com.vladoose.nir.integration.http.LimitedBytes;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * HTTP к WAHA внутри сети docker (спека whatsapp-waha §2). Ключ — в заголовке X-Api-Key; тексты ошибок — только название
 * операции и класс исключения: ни ключа, ни адреса (в адресе бывают номера клиентов). Каждый обмен — с дедлайном на весь
 * ответ (GatewayHttp). Файл берём только с адреса самой WAHA: ключ уходит в заголовке.
 */
@Component
public class WahaHttpClient implements WahaClient {

    private static final String GATEWAY = "WAHA";
    private static final int QR_MAX_BYTES = 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            // по умолчанию HttpClient на http:// просит «Upgrade: h2c», а WAHA такой запрос рвёт без ответа (её обработчик
            // WebSocket) — приём стоял бы с «WAHA недоступен». Найдено на живой WAHA 2026.9.1
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)     // ключ не должен уйти за перенаправлением
            .build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    /** Обычный вызов API — не дольше; скачивание файла WAHA'ой, сам файл и страница истории — не дольше longDeadline. */
    private final Duration callDeadline;
    private final Duration longDeadline;

    @Autowired
    public WahaHttpClient(ObjectMapper objectMapper,
                          @Value("${chats.whatsapp.waha.url:http://ais-waha:3000}") String url,
                          @Value("${chats.whatsapp.waha.api-key:}") String apiKey) {
        this(objectMapper, url, apiKey, Duration.ofSeconds(20), Duration.ofSeconds(90));
    }

    /** Короткие дедлайны — для тестов «зависшего» ответа. */
    WahaHttpClient(ObjectMapper objectMapper, String url, String apiKey, Duration callDeadline, Duration longDeadline) {
        this.objectMapper = objectMapper;
        String u = url == null ? "" : url.strip();
        this.baseUrl = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.callDeadline = callDeadline;
        this.longDeadline = longDeadline;
    }

    @Override
    public boolean isConfigured() { return !baseUrl.isEmpty() && !apiKey.isEmpty(); }

    @Override
    public WahaSession session(String name) {
        String what = "запросе состояния сессии";
        HttpResponse<String> r = call("GET", "/api/sessions/" + seg(name), null, callDeadline, what, 404);
        if (r.statusCode() == 404) return null;
        JsonNode s = json(r.body(), what);
        JsonNode me = s.path("me");
        return new WahaSession(s.path("name").asText(name), s.path("status").asText(""), text(me, "id"), text(me, "pushName"));
    }

    @Override
    public void createSession(String name, String market) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("start", true);
        ObjectNode config = body.putObject("config");
        config.putObject("metadata").put("market", market);
        // истории, каналы и рассылки — не переписка; группы нужны (фильтр «Группы» на «Чатах»)
        config.putObject("ignore").put("status", true).put("channels", true).put("broadcast", true).put("groups", false);
        // 409/422 — сессия уже есть (гонка двух стартов АИС) — это нас устраивает
        call("POST", "/api/sessions", body.toString(), callDeadline, "создании сессии", 409, 422);
    }

    @Override
    public void startSession(String name) {
        call("POST", "/api/sessions/" + seg(name) + "/start", null, callDeadline, "запуске сессии");
    }

    @Override
    public void restartSession(String name) {
        call("POST", "/api/sessions/" + seg(name) + "/restart", null, callDeadline, "перезапуске сессии");
    }

    @Override
    public void logoutSession(String name) {
        call("POST", "/api/sessions/" + seg(name) + "/logout", null, callDeadline, "отвязке номера");
    }

    @Override
    public byte[] qrPng(String session) {
        String what = "получении QR-кода";
        HttpRequest req = request("/api/" + seg(session) + "/auth/qr?format=image", callDeadline, "image/png").GET().build();
        HttpResponse<byte[]> r = GatewayHttp.exchange(http, req, info -> info.statusCode() / 100 == 2
                ? new LimitedBytes(QR_MAX_BYTES) : HttpResponse.BodySubscribers.replacing(new byte[0]), callDeadline, GATEWAY, what);
        check(r.statusCode(), what);
        String type = r.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (!type.startsWith("application/json")) return r.body();
        // часть версий отдаёт {mimetype, data: base64}
        String data = json(new String(r.body(), StandardCharsets.UTF_8), what).path("data").asText("");
        if (data.isEmpty()) throw new GatewayException(200, "WAHA: QR-код не пришёл");
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new GatewayException(200, "WAHA: QR-код не разобран");
        }
    }

    @Override
    public JsonNode message(String session, String chatId, String messageId, boolean downloadMedia) {
        String what = downloadMedia ? "подготовке файла сообщения" : "запросе сообщения";
        HttpResponse<String> r = call("GET", "/api/" + seg(session) + "/chats/" + seg(chatId) + "/messages/" + seg(messageId)
                + "?downloadMedia=" + downloadMedia, null, downloadMedia ? longDeadline : callDeadline, what);
        return json(r.body(), what);
    }

    @Override
    public byte[] downloadFile(String url, long maxBytes) {
        String what = "скачивании файла";
        requireConfigured();
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw new GatewayException(0, "WAHA: некорректная ссылка на файл");
        }
        if (!sameOrigin(uri)) throw new GatewayException(0, "WAHA: ссылка на файл ведёт не на WAHA — не скачиваем");
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(uri).timeout(longDeadline).header("X-Api-Key", apiKey).GET().build();
        } catch (IllegalArgumentException e) {
            throw new GatewayException(0, "WAHA: некорректная ссылка на файл");
        }
        HttpResponse<byte[]> r = GatewayHttp.exchange(http, req, info -> info.statusCode() / 100 == 2
                ? new LimitedBytes(maxBytes) : HttpResponse.BodySubscribers.replacing(new byte[0]), longDeadline, GATEWAY, what);
        check(r.statusCode(), what);
        return r.body();
    }

    @Override
    public List<JsonNode> history(String session, long fromEpochSec, int limit, int offset) {
        String what = "догонке по истории";
        HttpResponse<String> r = call("GET", "/api/" + seg(session) + "/chats/all/messages?filter.timestamp.gte=" + fromEpochSec
                + "&sortBy=timestamp&sortOrder=asc&limit=" + limit + "&offset=" + offset + "&downloadMedia=false",
                null, longDeadline, what);
        JsonNode arr = json(r.body(), what);
        if (!arr.isArray()) throw new GatewayException(200, "WAHA: история пришла не списком");
        List<JsonNode> out = new ArrayList<>(arr.size());
        arr.forEach(out::add);
        return out;
    }

    @Override
    public WahaContact contact(String session, String contactId) {
        String what = "запросе контакта";
        HttpResponse<String> r = call("GET", "/api/contacts?contactId=" + seg(contactId) + "&session=" + seg(session),
                null, callDeadline, what, 404);
        if (r.statusCode() == 404) return null;
        JsonNode c = json(r.body(), what);
        if (!c.isObject() || c.isEmpty()) return null;
        return new WahaContact(text(c, "id"), text(c, "name"), text(c, "pushname"));
    }

    @Override
    public String groupSubject(String session, String groupId) {
        String what = "запросе группы";
        HttpResponse<String> r = call("GET", "/api/" + seg(session) + "/groups/" + seg(groupId), null, callDeadline, what, 404);
        return r.statusCode() == 404 ? null : text(json(r.body(), what), "subject");
    }

    @Override
    public String lidPhone(String session, String lid) {
        String what = "запросе скрытого номера";
        HttpResponse<String> r = call("GET", "/api/" + seg(session) + "/lids/" + seg(lid), null, callDeadline, what, 404);
        if (r.statusCode() == 404) return null;
        String pn = text(json(r.body(), what), "pn");
        if (pn == null) return null;
        String digits = WahaEventParser.userOf(pn);
        return digits.matches("\\d{6,15}") ? "+" + digits : null;
    }

    private HttpResponse<String> call(String method, String pathAndQuery, String jsonBody, Duration deadline, String what,
                                      int... alsoOk) {
        HttpRequest.Builder b = request(pathAndQuery, deadline, "application/json");
        if (jsonBody != null) b.header("Content-Type", "application/json");
        HttpRequest req = b.method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build();
        HttpResponse<String> r = GatewayHttp.exchange(http, req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                deadline, GATEWAY, what);
        check(r.statusCode(), what, alsoOk);
        return r;
    }

    private HttpRequest.Builder request(String pathAndQuery, Duration deadline, String accept) {
        requireConfigured();
        try {
            return HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).timeout(deadline)
                    .header("X-Api-Key", apiKey).header("Accept", accept);
        } catch (IllegalArgumentException e) {
            // текст исключения JDK может нести адрес или значение заголовка — наружу только своё
            throw new GatewayAuthException(0, "WAHA: запрос не собирается — проверьте WHATSAPP_WAHA_URL и WHATSAPP_WAHA_API_KEY");
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new GatewayAuthException(0, "не заданы адрес или ключ WAHA (WHATSAPP_WAHA_URL / WHATSAPP_WAHA_API_KEY)");
        }
    }

    private static void check(int status, String what, int... alsoOk) {
        if (status / 100 == 2) return;
        for (int ok : alsoOk) {
            if (status == ok) return;
        }
        if (status == 401 || status == 403) {
            throw new GatewayAuthException(status, "WAHA отклонил ключ API (HTTP " + status + ") при " + what
                    + " — проверьте WHATSAPP_WAHA_API_KEY");
        }
        throw new GatewayException(status, "WAHA: HTTP " + status + " при " + what);
    }

    private JsonNode json(String body, String what) {
        if (body == null || body.isBlank()) return MissingNode.getInstance();
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new GatewayException(200, "WAHA: ответ не разобран при " + what);
        }
    }

    /** Ссылку на файл собирает сама WAHA из WAHA_BASE_URL — она должна вести туда же, куда ходим мы. */
    private boolean sameOrigin(URI u) {
        try {
            URI base = URI.create(baseUrl);
            return u.getScheme() != null && u.getHost() != null && base.getScheme() != null && base.getHost() != null
                    && u.getScheme().equalsIgnoreCase(base.getScheme()) && u.getHost().equalsIgnoreCase(base.getHost())
                    && port(u) == port(base);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int port(URI u) {
        if (u.getPort() != -1) return u.getPort();
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private static String seg(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }
}
