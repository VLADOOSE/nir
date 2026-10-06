package com.vladoose.nir.integration.skpharmacy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.exception.UpstreamException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkPharmacyHttpClientTest {

    static HttpServer server;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sk-stub");
            t.setDaemon(true);
            return t;
        }));
        // заголовки пришли, тело встало
        server.createContext("/stall/ru/searchanno", ex -> {
            ex.sendResponseHeaders(200, 1000);
            OutputStream os = ex.getResponseBody();
            os.write("0123456789".getBytes(StandardCharsets.UTF_8));
            os.flush();
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) { }
            ex.close();
        });
        server.createContext("/e500/ru/searchanno", ex -> send(ex, 500, "boom"));
        server.createContext("/e429/ru/searchanno", ex -> send(ex, 429, "slow down"));
        server.createContext("/e404/ru/searchanno", ex -> send(ex, 404, "nope"));
        server.createContext("/redir/ru/searchanno", ex -> {
            ex.getResponseHeaders().add("Location", "/redir/ru/searchanno2");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/redir/ru/searchanno2", ex -> send(ex, 200, "<html>ok</html>"));
        server.createContext("/pdf/big", ex -> {
            byte[] chunk = new byte[64 * 1024];
            ex.sendResponseHeaders(200, 0);                               // chunked, длина заранее не известна
            try (OutputStream os = ex.getResponseBody()) {
                for (int written = 0; written <= 1024 * 1024; written += chunk.length) os.write(chunk);
            } catch (IOException ignored) {                               // клиент оборвал на пределе
            }
        });
        server.createContext("/pdf/gone", ex -> send(ex, 404, "gone"));
        server.createContext("/pdf/e503", ex -> send(ex, 503, "busy"));
        server.createContext("/pdf/ok", ex -> send(ex, 200, "%PDF-1.4"));
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static String base(String prefix) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + prefix;
    }

    @Test
    void stalledBody_failsWithReason_retryable() {
        SkPharmacyHttpClient c = new SkPharmacyHttpClient(base("stall"), Duration.ofSeconds(1));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> c.searchPage(1)).isInstanceOfSatisfying(SkCallException.class, e -> {
            assertThat(e.retryable()).isTrue();
            assertThat(e.getMessage()).isEqualTo("Сеть fms.ecc.kz: нет ответа за 1 с");
        });
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void http500And429_retryable_404_not_pathWithoutHost() {
        assertThatThrownBy(() -> new SkPharmacyHttpClient(base("e500"), Duration.ofSeconds(5)).searchPage(1))
                .isInstanceOfSatisfying(SkCallException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).isEqualTo("fms.ecc.kz вернул 500 для /e500/ru/searchanno");
                });
        assertThatThrownBy(() -> new SkPharmacyHttpClient(base("e429"), Duration.ofSeconds(5)).searchPage(1))
                .isInstanceOfSatisfying(SkCallException.class, e -> assertThat(e.retryable()).isTrue());
        assertThatThrownBy(() -> new SkPharmacyHttpClient(base("e404"), Duration.ofSeconds(5)).searchPage(2))
                .isInstanceOfSatisfying(SkCallException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).isEqualTo("fms.ecc.kz вернул 404 для /e404/ru/searchanno");
                });
    }

    @Test
    void redirectFollowed() {
        assertThat(new SkPharmacyHttpClient(base("redir"), Duration.ofSeconds(5)).searchPage(1)).contains("ok");
    }

    @Test
    void pdfOverLimit_notRetryable() {
        SkTechSpecHttpClient c = new SkTechSpecHttpClient(base(""), Duration.ofSeconds(5), Duration.ofSeconds(10),
                1024 * 1024);
        assertThatThrownBy(() -> c.downloadFile(base("pdf/big")))
                .isInstanceOfSatisfying(SkCallException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).isEqualTo("Сеть fms.ecc.kz: ответ больше 1 МБ");
                });
    }

    @Test
    void pdf404IsNull_503Retryable_200Body() {
        SkTechSpecHttpClient c = new SkTechSpecHttpClient(base(""), Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThat(c.downloadFile(base("pdf/gone"))).isNull();
        assertThatThrownBy(() -> c.downloadFile(base("pdf/e503")))
                .isInstanceOfSatisfying(SkCallException.class, e -> assertThat(e.retryable()).isTrue());
        assertThat(new String(c.downloadFile(base("pdf/ok")), StandardCharsets.UTF_8)).isEqualTo("%PDF-1.4");
    }

    @Test
    void unreachablePortalNamesTheFailureInsteadOfNull() throws IOException {
        SkPharmacyHttpClient client = new SkPharmacyHttpClient("http://127.0.0.1:" + closedPort());

        assertThatThrownBy(() -> client.searchPage(1))
                .isInstanceOf(UpstreamException.class)
                .hasMessage("Сеть fms.ecc.kz: ConnectException");
    }

    /** Кнопка «ТЗ» у лота СК-Фармации показывает этот текст оператору (502 — наследник UpstreamException). */
    @Test
    void unreachablePortalOnTechSpecNamesTheFailureInsteadOfNull() throws IOException {
        SkTechSpecHttpClient client = new SkTechSpecHttpClient("http://127.0.0.1:" + closedPort());

        assertThatThrownBy(() -> client.fetchTechSpecRefs("521464"))
                .isInstanceOfSatisfying(SkCallException.class, e -> assertThat(e.retryable()).isTrue())
                .hasMessage("Сеть fms.ecc.kz: ConnectException");
    }

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }
}
