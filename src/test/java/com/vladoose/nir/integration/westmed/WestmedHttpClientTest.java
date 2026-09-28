package com.vladoose.nir.integration.westmed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.integration.westmed.dto.WestmedPage;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Формы ответов — как у реального API сайта (westmed, коммит e87bfcb). Стаб на JDK HttpServer, без сети. */
class WestmedHttpClientTest {

    static HttpServer server;
    static int port;
    /** "METHOD path?query auth=<заголовок|none> body=<тело>" */
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final AtomicInteger logins = new AtomicInteger();
    static volatile int loginStatus;
    static volatile String validToken;     // какой токен принимают admin-вызовы
    static volatile int rejectStatus;      // чем отвечать на чужой токен (реальный сайт — 403)
    static volatile String adminBody;
    static volatile String productsBody;
    /** Заголовки и начало тела — и тишина: соединение не рвётся, тело не приходит. */
    static volatile boolean stall;

    static final String PASSWORD = "S3cr3t-pass";

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());   // «зависший» ответ не держит остальные тесты
        server.createContext("/", ex -> {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getRawPath();
            String query = ex.getRequestURI().getRawQuery();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(method + " " + path + (query != null ? "?" + query : "")
                    + " auth=" + (auth == null ? "none" : auth) + " body=" + body);
            if (stall) {
                ex.sendResponseHeaders(200, 100);
                OutputStream os = ex.getResponseBody();
                os.write("{\"cont".getBytes(StandardCharsets.UTF_8));
                os.flush();
                try { Thread.sleep(8000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                ex.close();
                return;
            }
            int status;
            String resp;
            if (path.equals("/api/auth/login")) {
                int n = logins.incrementAndGet();
                status = loginStatus;
                resp = status == 200 ? "{\"accessToken\":\"tok-" + n + "\",\"refreshToken\":\"r\"}" : "";
            } else if (path.startsWith("/api/admin/")) {
                if (!("Bearer " + validToken).equals(auth)) { status = rejectStatus; resp = ""; }
                else if (method.equals("PATCH")) { status = 200; resp = "{}"; }
                else { status = 200; resp = adminBody; }
            } else if (path.equals("/api/v1/products")) {
                status = 200;
                resp = productsBody;
            } else {
                status = 404;
                resp = "";
            }
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
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
        logins.set(0);
        loginStatus = 200;
        validToken = "tok-1";
        rejectStatus = 403;
        adminBody = "{\"content\":[],\"last\":true,\"totalPages\":0,\"number\":0}";
        productsBody = "{\"content\":[],\"last\":true,\"totalPages\":0,\"number\":0}";
        stall = false;
    }

    /**
     * Таймаут HttpRequest в JDK 17 снимается на заголовках: вставшее тело держало бы поток приёма (и опрос сайта,
     * и разбор корзины в чатах WhatsApp) вечно. Дедлайн — на весь обмен (перепроверка ревью 2026-09-28).
     */
    @Test
    void stalledBodyIsCutOffByDeadline() {
        stall = true;
        WestmedHttpClient c = new WestmedHttpClient(new ObjectMapper(), "http://localhost:" + port, "u", "p", Duration.ofSeconds(2));
        long t0 = System.nanoTime();

        assertThatThrownBy(() -> c.searchProducts("облучатель", 5))
                .isInstanceOfSatisfying(WestmedApiException.class, e -> assertThat(e.status()).isZero());

        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    }

    private static WestmedHttpClient client(String password) {
        return new WestmedHttpClient(new ObjectMapper(), "http://localhost:" + port + "/", "ais@westmed.kz", password);
    }

    private static List<String> adminCalls() {
        return calls.stream().filter(c -> c.contains(" /api/admin/")).toList();
    }

    @Test
    void logsInOnceAndSendsBearerToAdminCalls() {
        WestmedHttpClient c = client(PASSWORD);
        c.fetchPriceRequests(0, 50);
        c.fetchQuoteRequests(0, 50);

        assertThat(logins.get()).isEqualTo(1);
        assertThat(adminCalls()).hasSize(2).allMatch(s -> s.contains("auth=Bearer tok-1"));
        assertThat(adminCalls().get(0)).startsWith("GET /api/admin/requests?page=0&size=50");
    }

    @Test
    void expiredTokenAnswered403TriggersOneReloginAndRetry() {
        validToken = "tok-2";   // первый токен сайт уже не принимает и отвечает 403 — как реальный westmed
        WestmedHttpClient c = client(PASSWORD);

        c.fetchPriceRequests(0, 50);

        assertThat(logins.get()).isEqualTo(2);
        assertThat(adminCalls()).last().asString().contains("auth=Bearer tok-2");
    }

    @Test
    void expiredTokenAnswered401AlsoTriggersRelogin() {
        validToken = "tok-2";
        rejectStatus = 401;

        client(PASSWORD).fetchQuoteRequests(0, 50);

        assertThat(logins.get()).isEqualTo(2);
    }

    @Test
    void persistentRefusalIsAuthErrorAfterExactlyOneRelogin() {
        validToken = "never";
        WestmedHttpClient c = client(PASSWORD);

        assertThatThrownBy(() -> c.fetchPriceRequests(0, 50)).isInstanceOf(WestmedAuthException.class);
        assertThat(logins.get()).isEqualTo(2);
    }

    @Test
    void wrongPasswordIsAuthErrorWithoutLeakingPassword() {
        loginStatus = 401;
        assertThatThrownBy(() -> client(PASSWORD).fetchPriceRequests(0, 50))
                .isInstanceOf(WestmedAuthException.class)
                .hasMessageContaining("неверный логин")
                .hasMessageNotContaining(PASSWORD);
    }

    @Test
    void rateLimitedLoginIsAuthError() {
        loginStatus = 429;
        assertThatThrownBy(() -> client(PASSWORD).fetchPriceRequests(0, 50))
                .isInstanceOf(WestmedAuthException.class).hasMessageContaining("429");
    }

    @Test
    void missingCredentialsAreReportedWithoutNetwork() {
        WestmedHttpClient c = client("");
        assertThat(c.isConfigured()).isFalse();
        assertThatThrownBy(() -> c.fetchPriceRequests(0, 50))
                .isInstanceOf(WestmedAuthException.class).hasMessageContaining("не заданы");
        assertThat(calls).isEmpty();
    }

    @Test
    void parsesRealPageShapeWithAbsentOptionalFields() {
        // @JsonInclude(NON_NULL) на сайте: пустых полей в JSON просто НЕТ
        adminBody = """
            {"content":[
               {"id":"8a1f0c1e-0000-4000-8000-000000000001","name":"Айгерим","email":"a@clinic.kz",
                "status":"NEW","createdAt":"2026-09-20T08:15:30.123456Z"},
               {"id":"8a1f0c1e-0000-4000-8000-000000000002","name":"Иван","email":"i@x.kz","phone":"+77770752770",
                "company":"ТОО «Клиника»","message":"Нужен облучатель","productName":"Облучатель ОБН-150",
                "status":"PROCESSED","createdAt":"2026-09-19T10:00:00Z"}],
             "pageable":{"pageNumber":0,"pageSize":50},"last":true,"totalPages":1,"totalElements":2,
             "number":0,"size":50,"first":true,"numberOfElements":2,"empty":false}
            """;

        WestmedPage<WestmedPriceRequest> page = client(PASSWORD).fetchPriceRequests(0, 50);

        assertThat(page.isLast()).isTrue();
        assertThat(page.contentOrEmpty()).hasSize(2);
        WestmedPriceRequest general = page.contentOrEmpty().get(0);
        assertThat(general.phone()).isNull();
        assertThat(general.productName()).isNull();
        assertThat(general.createdAt()).isEqualTo("2026-09-20T08:15:30.123456Z");
        assertThat(page.contentOrEmpty().get(1).productName()).isEqualTo("Облучатель ОБН-150");
    }

    @Test
    void parsesQuoteItems() {
        adminBody = """
            {"content":[{"id":"q-1","name":"Айгерим","email":"a@clinic.kz","status":"NEW",
               "items":[{"productSlug":"obn-150","productName":"Облучатель ОБН-150","quantity":2}],
               "createdAt":"2026-09-20T08:15:30Z"}],"last":true,"totalPages":1,"number":0}
            """;

        WestmedQuoteRequest q = client(PASSWORD).fetchQuoteRequests(0, 50).contentOrEmpty().get(0);

        assertThat(q.items()).hasSize(1);
        assertThat(q.items().get(0).productSlug()).isEqualTo("obn-150");
        assertThat(q.items().get(0).quantity()).isEqualTo(2);
    }

    @Test
    void updateStatusSendsPatchWithJsonBody() {
        client(PASSWORD).updateStatus(WestmedKind.QUOTE, "q-1", "PROCESSED");

        assertThat(adminCalls()).singleElement().asString()
                .startsWith("PATCH /api/admin/quote-requests/q-1/status")
                .endsWith("body={\"status\":\"PROCESSED\"}");
    }

    @Test
    void searchProductsEncodesCyrillicAndSendsNoToken() {
        productsBody = """
            {"content":[{"id":"p1","name":"Облучатель «Азов»","slug":"obluchatel-azov","brandName":"Азов"}],
             "last":true,"totalPages":1,"number":0}
            """;

        List<WestmedProduct> found = client(PASSWORD).searchProducts("Облучатель «Азов»", 20);

        assertThat(found).singleElement().satisfies(p -> {
            assertThat(p.slug()).isEqualTo("obluchatel-azov");
            assertThat(p.brandName()).isEqualTo("Азов");
        });
        String call = calls.get(0);
        assertThat(call).contains("auth=none");
        String query = call.substring(call.indexOf('?') + 1, call.indexOf(" auth="));
        assertThat(URLDecoder.decode(query, StandardCharsets.UTF_8)).isEqualTo("search=Облучатель «Азов»&size=20");
        assertThat(logins.get()).isZero();
    }

    @Test
    void siteDownIsApiErrorWithStatusZero() throws Exception {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) { freePort = s.getLocalPort(); }
        WestmedHttpClient c = new WestmedHttpClient(new ObjectMapper(), "http://localhost:" + freePort, "u", "p");

        assertThatThrownBy(() -> c.searchProducts("x", 5))
                .isInstanceOfSatisfying(WestmedApiException.class, e -> {
                    assertThat(e.status()).isZero();
                    assertThat(e.getMessage()).startsWith("сайт недоступен");
                });
    }

    @Test
    void kindRoundTripsExternalIds() {
        assertThat(WestmedKind.PRICE.externalId("u1")).isEqualTo("price:u1");
        assertThat(WestmedKind.ofExternalId("quote:q-9")).isEqualTo(WestmedKind.QUOTE);
        assertThat(WestmedKind.siteIdOf("quote:q-9")).isEqualTo("q-9");
        assertThatThrownBy(() -> WestmedKind.ofExternalId("other:1")).isInstanceOf(IllegalArgumentException.class);
    }
}
