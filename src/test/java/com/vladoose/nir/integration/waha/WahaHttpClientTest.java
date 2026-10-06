package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.integration.http.FileTooLargeException;
import com.vladoose.nir.integration.whatsapp.GatewayAuthException;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/** Стаб WAHA на JDK HttpServer, без сети (спека whatsapp-waha §2, §12). */
class WahaHttpClientTest {

    static final String KEY = "waha-key-secret-123";
    static HttpServer server;
    static int port;
    /** "METHOD path?query" */
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final List<String> keys = new CopyOnWriteArrayList<>();
    static final List<String> bodies = new CopyOnWriteArrayList<>();
    /** "METHOD path" (без query) → ответ; нет — 404. */
    static final Map<String, Resp> routes = new ConcurrentHashMap<>();
    /** Заголовки и начало тела — и тишина. */
    static volatile boolean stall;
    /** Запросы с заголовком Upgrade — настоящая WAHA рвёт их без ответа. */
    static final List<String> upgrades = new CopyOnWriteArrayList<>();

    record Resp(int status, String contentType, byte[] body) {
        static Resp json(int status, String json) {
            return new Resp(status, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());   // «зависший» ответ не держит остальные тесты
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath();
            // как настоящая WAHA 2026.9.1: запрос с Upgrade её обработчик WebSocket закрывает без ответа («Empty reply»)
            if (ex.getRequestHeaders().containsKey("Upgrade")) {
                upgrades.add(ex.getRequestMethod() + " " + path);
                ex.close();
                return;
            }
            String query = ex.getRequestURI().getRawQuery();
            calls.add(ex.getRequestMethod() + " " + path + (query != null ? "?" + query : ""));
            keys.add(String.valueOf(ex.getRequestHeaders().getFirst("X-Api-Key")));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (stall) {
                ex.sendResponseHeaders(200, 100);
                OutputStream os = ex.getResponseBody();
                os.write("{\"na".getBytes(StandardCharsets.UTF_8));
                os.flush();
                try { Thread.sleep(8000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                ex.close();
                return;
            }
            Resp r = routes.getOrDefault(ex.getRequestMethod() + " " + path, new Resp(404, "application/json", new byte[0]));
            if (r.contentType() != null) ex.getResponseHeaders().add("Content-Type", r.contentType());
            ex.sendResponseHeaders(r.status(), r.body().length == 0 ? -1 : r.body().length);
            if (r.body().length > 0) {
                try (OutputStream os = ex.getResponseBody()) { os.write(r.body()); }
            }
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() { server.stop(0); }

    @BeforeEach
    void reset() {
        calls.clear();
        keys.clear();
        bodies.clear();
        routes.clear();
        upgrades.clear();
        stall = false;
    }

    static String base() { return "http://localhost:" + port; }

    static WahaHttpClient client() { return new WahaHttpClient(new ObjectMapper(), base() + "/", KEY); }

    @Test
    void sessionStatusAndMeWithKeyInHeaderOnly() {
        routes.put("GET /api/sessions/westmed", Resp.json(200,
                "{\"name\":\"westmed\",\"status\":\"WORKING\",\"me\":{\"id\":\"77000000001@c.us\",\"pushName\":\"West-Med\"}}"));

        assertThat(client().session("westmed")).isEqualTo(new WahaSession("westmed", "WORKING", "77000000001@c.us", "West-Med"));
        assertThat(keys).containsExactly(KEY);
        assertThat(calls).singleElement().asString().doesNotContain(KEY);
    }

    /**
     * HttpClient по умолчанию просит HTTP/2 и на http:// шлёт «Upgrade: h2c» — живая WAHA рвёт такое соединение без ответа,
     * и приём стоял с «WAHA недоступен» (найдено на настоящей WAHA, задача 13; стаб на Node заголовок игнорировал).
     */
    @Test
    void speaksPlainHttp11BecauseWahaDropsUpgradeRequests() {
        routes.put("GET /api/sessions/westmed", Resp.json(200, "{\"name\":\"westmed\",\"status\":\"WORKING\"}"));

        assertThat(client().session("westmed")).isNotNull();
        assertThat(upgrades).isEmpty();
    }

    @Test
    void missingSessionIsNull() {
        assertThat(client().session("westmed")).isNull();
    }

    @Test
    void createSessionSendsMarketAndIgnoreConfig() throws Exception {
        routes.put("POST /api/sessions", Resp.json(201, "{\"name\":\"westmed\",\"status\":\"STARTING\"}"));

        client().createSession("westmed", "KZ");

        JsonNode body = new ObjectMapper().readTree(bodies.get(0));
        assertThat(body.path("name").asText()).isEqualTo("westmed");
        assertThat(body.path("start").asBoolean()).isTrue();
        assertThat(body.at("/config/metadata/market").asText()).isEqualTo("KZ");
        assertThat(body.at("/config/ignore/status").asBoolean()).isTrue();
        assertThat(body.at("/config/ignore/channels").asBoolean()).isTrue();
        assertThat(body.at("/config/ignore/broadcast").asBoolean()).isTrue();
        assertThat(body.at("/config/ignore/groups").asBoolean()).isFalse();
    }

    /** Сессию уже создал параллельный старт АИС — не ошибка. */
    @Test
    void createSessionToleratesExisting() {
        routes.put("POST /api/sessions", Resp.json(422, "{\"message\":\"Session already exists\"}"));

        assertThatCode(() -> client().createSession("westmed", "KZ")).doesNotThrowAnyException();
    }

    @Test
    void startRestartLogoutHitTheirPaths() {
        routes.put("POST /api/sessions/westmed/start", Resp.json(201, "{}"));
        routes.put("POST /api/sessions/westmed/restart", Resp.json(201, "{}"));
        routes.put("POST /api/sessions/westmed/logout", Resp.json(201, "{}"));
        WahaHttpClient c = client();

        c.startSession("westmed");
        c.restartSession("westmed");
        c.logoutSession("westmed");

        assertThat(calls).containsExactly("POST /api/sessions/westmed/start", "POST /api/sessions/westmed/restart",
                "POST /api/sessions/westmed/logout");
    }

    @Test
    void qrComesAsPngOrBase64Json() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        routes.put("GET /api/westmed/auth/qr", new Resp(200, "image/png", png));
        assertThat(client().qrPng("westmed")).containsExactly(png);

        routes.put("GET /api/westmed/auth/qr", Resp.json(200,
                "{\"mimetype\":\"image/png\",\"data\":\"" + Base64.getEncoder().encodeToString(png) + "\"}"));
        assertThat(client().qrPng("westmed")).containsExactly(png);
    }

    @Test
    void messageByIdEncodesSegmentsAndAsksForMedia() {
        String id = "false_77011234567@c.us_3EB0A1";
        routes.put("GET /api/westmed/chats/77011234567%40c.us/messages/false_77011234567%40c.us_3EB0A1",
                Resp.json(200, "{\"id\":\"" + id + "\",\"media\":{\"url\":\"" + base() + "/api/files/westmed/a.pdf\"}}"));

        JsonNode m = client().message("westmed", "77011234567@c.us", id, true);

        assertThat(m.at("/media/url").asText()).endsWith("/api/files/westmed/a.pdf");
        assertThat(calls).singleElement().asString().endsWith("?downloadMedia=true");
    }

    @Test
    void historyPagesFromTimestamp() {
        routes.put("GET /api/westmed/chats/all/messages", Resp.json(200, "[{\"id\":\"a\"},{\"id\":\"b\"}]"));

        List<JsonNode> page = client().history("westmed", 1_790_000_000L, 100, 200);

        assertThat(page).extracting(n -> n.path("id").asText()).containsExactly("a", "b");
        assertThat(calls.get(0)).contains("filter.timestamp.gte=1790000000").contains("sortBy=timestamp")
                .contains("sortOrder=asc").contains("limit=100").contains("offset=200").contains("downloadMedia=false");
    }

    /** Файл — только с адреса самой WAHA: ключ API уходит в заголовке, чужому хосту его не отдаём. */
    @Test
    void fileOnlyFromWahaItselfAndCutAtLimit() {
        routes.put("GET /api/files/westmed/a.pdf", new Resp(200, "application/pdf", new byte[]{1, 2, 3, 4, 5}));
        WahaHttpClient c = client();

        assertThat(c.downloadFile(base() + "/api/files/westmed/a.pdf", 10)).containsExactly(1, 2, 3, 4, 5);
        assertThat(keys).containsExactly(KEY);
        assertThatThrownBy(() -> c.downloadFile(base() + "/api/files/westmed/a.pdf", 4)).isInstanceOf(FileTooLargeException.class);
        calls.clear();
        assertThatThrownBy(() -> c.downloadFile("http://files.example/a.pdf", 10))
                .isInstanceOf(GatewayException.class).hasMessageContaining("не на WAHA");
        assertThat(calls).isEmpty();
    }

    @Test
    void contactsGroupsAndHiddenNumbers() {
        routes.put("GET /api/contacts", Resp.json(200,
                "{\"id\":\"77011234567@c.us\",\"number\":\"77011234567\",\"name\":\"Айгерим (клиника)\",\"pushname\":\"Aigerim\"}"));
        routes.put("GET /api/westmed/groups/120363000000000001%40g.us",
                Resp.json(200, "{\"id\":\"120363000000000001@g.us\",\"subject\":\"Коллеги\"}"));
        routes.put("GET /api/westmed/lids/123456789012345%40lid",
                Resp.json(200, "{\"lid\":\"123456789012345@lid\",\"pn\":\"77012223344@c.us\"}"));
        WahaHttpClient c = client();

        assertThat(c.contact("westmed", "77011234567@c.us"))
                .isEqualTo(new WahaContact("77011234567@c.us", "Айгерим (клиника)", "Aigerim"));
        assertThat(calls.get(0)).isEqualTo("GET /api/contacts?contactId=77011234567%40c.us&session=westmed");
        assertThat(c.groupSubject("westmed", "120363000000000001@g.us")).isEqualTo("Коллеги");
        assertThat(c.lidPhone("westmed", "123456789012345@lid")).isEqualTo("+77012223344");
        assertThat(c.groupSubject("westmed", "120363000000000009@g.us")).isNull();
        assertThat(c.lidPhone("westmed", "999@lid")).isNull();
    }

    @Test
    void rejectedKeyIsAuthErrorWithoutKeyOrAddress() {
        routes.put("GET /api/sessions/westmed", Resp.json(401, "{\"message\":\"Unauthorized\"}"));

        assertThatThrownBy(() -> client().session("westmed"))
                .isInstanceOf(GatewayAuthException.class)
                .hasMessageContaining("401").hasMessageNotContaining(KEY).hasMessageNotContaining("localhost");
    }

    @Test
    void serverErrorKeepsStatusWithoutAddress() {
        routes.put("POST /api/sessions/westmed/restart", Resp.json(500, "{}"));

        assertThatThrownBy(() -> client().restartSession("westmed"))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("localhost");
                });
    }

    @Test
    void unreachableIsStatusZeroWithoutKeyOrAddress() throws Exception {
        int free;
        try (ServerSocket s = new ServerSocket(0)) { free = s.getLocalPort(); }
        WahaHttpClient c = new WahaHttpClient(new ObjectMapper(), "http://localhost:" + free, KEY);

        assertThatThrownBy(() -> c.session("westmed"))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.status()).isZero();
                    assertThat(e.getMessage()).startsWith("WAHA недоступен").doesNotContain(KEY).doesNotContain(String.valueOf(free));
                });
    }

    /** Таймаут HttpRequest в JDK 17 снимается на заголовках: вставшее тело держало бы поток приёма вечно. */
    @Test
    void stalledBodyIsCutOffByDeadline() {
        stall = true;
        WahaHttpClient c = new WahaHttpClient(new ObjectMapper(), base(), KEY, Duration.ofSeconds(1), Duration.ofSeconds(2));
        long t0 = System.nanoTime();

        assertThatThrownBy(() -> c.session("westmed")).isInstanceOf(GatewayException.class).hasMessageContaining("не ответил");
        assertThatThrownBy(() -> c.downloadFile(base() + "/api/files/westmed/a.pdf", 1000)).isInstanceOf(GatewayException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(6));
    }

    @Test
    void missingKeyIsNotConfiguredAndNoNetwork() {
        WahaHttpClient c = new WahaHttpClient(new ObjectMapper(), base(), "");

        assertThat(c.isConfigured()).isFalse();
        assertThatThrownBy(() -> c.session("westmed")).isInstanceOf(GatewayAuthException.class).hasMessageContaining("не заданы");
        assertThat(calls).isEmpty();
    }

    /** Ключ с переводом строки (кривой .env): текст исключения JDK мог бы нести значение заголовка — наружу только своё. */
    @Test
    void malformedKeyNeverLeaksIntoErrorText() {
        WahaHttpClient c = new WahaHttpClient(new ObjectMapper(), base(), "waha-secret\nX-Evil: 1");

        assertThatThrownBy(() -> c.session("westmed"))
                .isInstanceOf(GatewayAuthException.class).hasMessageNotContaining("waha-secret");
        assertThat(calls).isEmpty();
    }
}
