package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.integration.whatsapp.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/** Стаб Green-API на JDK HttpServer, без сети. Формы ответов — из документации (спека §2). */
class GreenApiHttpClientTest {

    static final String TOKEN = "tok-secret-123";
    static HttpServer server;
    static int port;
    /** "METHOD path?query" */
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static volatile int status;
    static volatile String body;
    static volatile byte[] file;
    /** Заголовки и начало тела — и тишина: соединение не рвётся, тело не приходит. */
    static volatile boolean stall;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());   // «зависший» ответ не держит остальные тесты
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath();
            String query = ex.getRequestURI().getRawQuery();
            calls.add(ex.getRequestMethod() + " " + path + (query != null ? "?" + query : ""));
            if (stall) {
                ex.sendResponseHeaders(200, 100);
                OutputStream os = ex.getResponseBody();
                os.write("{\"rec".getBytes(StandardCharsets.UTF_8));
                os.flush();
                try { Thread.sleep(8000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                ex.close();
                return;
            }
            byte[] b = path.startsWith("/files/") ? file : body.getBytes(StandardCharsets.UTF_8);
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
        status = 200;
        body = "null";
        file = new byte[0];
        stall = false;
    }

    static GreenApiHttpClient client() {
        return new GreenApiHttpClient(new ObjectMapper(), "http://localhost:" + port + "/", "1101", TOKEN);
    }

    @Test
    void emptyQueueIsNull() {
        assertThat(client().receive(5)).isNull();
        assertThat(calls).containsExactly("GET /waInstance1101/receiveNotification/" + TOKEN + "?receiveTimeout=5");
    }

    @Test
    void receivedNotificationCarriesReceiptAndBody() {
        body = """
            {"receiptId":1234567,"body":{"typeWebhook":"incomingMessageReceived","idMessage":"F7AE"}}
            """;

        GreenApiReceived r = client().receive(20);

        assertThat(r.receiptId()).isEqualTo(1234567L);
        assertThat(r.body().path("idMessage").asText()).isEqualTo("F7AE");
    }

    @Test
    void deleteSendsReceiptIdInPath() {
        body = "{\"result\":true}";
        client().delete(1234567);
        assertThat(calls).containsExactly("DELETE /waInstance1101/deleteNotification/" + TOKEN + "/1234567");
    }

    @Test
    void stateAndSettingsAreParsed() {
        body = "{\"stateInstance\":\"notAuthorized\"}";
        assertThat(client().state()).isEqualTo("notAuthorized");

        body = """
            {"wid":"77000000001@c.us","webhookUrl":"","incomingWebhook":"yes","outgoingMessageWebhook":"no",
             "outgoingWebhook":"yes","stateWebhook":"no"}
            """;
        GreenApiSettings s = client().settings();
        assertThat(s.wid()).isEqualTo("77000000001@c.us");
        assertThat(s.webhookUrl()).isEmpty();
        assertThat(s.incomingWebhook()).isTrue();
        assertThat(s.outgoingMessageWebhook()).isFalse();
    }

    @Test
    void rejectedKeyIsAuthErrorWithoutTokenOrUrlInMessage() {
        status = 401;
        body = "";
        assertThatThrownBy(() -> client().receive(5))
                .isInstanceOf(GatewayAuthException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining(TOKEN)
                .hasMessageNotContaining("waInstance");
        status = 403;
        assertThatThrownBy(() -> client().state()).isInstanceOf(GatewayAuthException.class);
    }

    @Test
    void quota466IsQuotaError() {
        status = 466;
        body = "";
        assertThatThrownBy(() -> client().receive(5)).isInstanceOf(GatewayQuotaException.class);
    }

    @Test
    void serverErrorKeepsStatusAndHidesToken() {
        status = 500;
        body = "";
        assertThatThrownBy(() -> client().delete(1))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.getMessage()).doesNotContain(TOKEN).doesNotContain("waInstance");
                });
    }

    @Test
    void unreachableServiceIsStatusZeroWithoutToken() throws Exception {
        int free;
        try (ServerSocket s = new ServerSocket(0)) { free = s.getLocalPort(); }
        GreenApiHttpClient c = new GreenApiHttpClient(new ObjectMapper(), "http://localhost:" + free, "1101", TOKEN);

        assertThatThrownBy(() -> c.receive(5))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.status()).isZero();
                    assertThat(e.getMessage()).startsWith("Green-API недоступен").doesNotContain(TOKEN);
                });
    }

    @Test
    void downloadReturnsBytesAndStopsAtLimit() {
        file = new byte[]{1, 2, 3, 4, 5};
        String url = "http://localhost:" + port + "/files/a.pdf";

        assertThat(client().download(url, 10)).containsExactly(1, 2, 3, 4, 5);
        assertThatThrownBy(() -> client().download(url, 4)).isInstanceOf(FileTooLargeException.class);
    }

    @Test
    void httpsServiceRefusesPlainHttpDownload() {
        GreenApiHttpClient c = new GreenApiHttpClient(new ObjectMapper(), "https://api.green-api.example", "1101", TOKEN);

        assertThatThrownBy(() -> c.download("http://localhost:" + port + "/files/a.pdf", 10))
                .isInstanceOf(GatewayException.class).hasMessageContaining("https");
        assertThat(calls).isEmpty();
    }

    /**
     * Ревью 2026-09-28: при опечатке в настройках JDK кладёт в текст IllegalArgumentException ВЕСЬ адрес — с токеном,
     * а текст уходил в лог и в строку состояния, которую видит любой вошедший.
     */
    @Test
    void malformedSettingsNeverPutTokenIntoErrorText() {
        GreenApiHttpClient oneSlash = new GreenApiHttpClient(new ObjectMapper(), "https:/7105.api.greenapi.com", "1101", TOKEN);
        GreenApiHttpClient spaced = new GreenApiHttpClient(new ObjectMapper(), "http://localhost:" + port, "1101", "tok secret\"123");

        for (GreenApiHttpClient c : List.of(oneSlash, spaced)) {
            assertThatThrownBy(() -> c.receive(5))
                    .isInstanceOf(GatewayAuthException.class)
                    .hasMessageContaining("WHATSAPP_API_URL")
                    .hasMessageNotContaining(TOKEN)
                    .hasMessageNotContaining("secret")
                    .hasMessageNotContaining("waInstance");
        }
        assertThat(calls).isEmpty();
    }

    /** Ссылка без хоста проходит URI.create, но не HttpRequest — это сбой скачивания, а не «ядовитое» уведомление. */
    @Test
    void badFileLinkIsDownloadFailure() {
        assertThatThrownBy(() -> client().download("https:/files.example/a.pdf", 10))
                .isInstanceOf(GatewayException.class);
    }

    /**
     * Таймаут HttpRequest в JDK 17 снимается на заголовках: тело, которое встало посередине, держало единственный поток
     * приёма вечно. Дедлайн — на весь обмен, включая тело.
     */
    @Test
    void stalledBodyIsCutOffByDeadline() {
        stall = true;
        GreenApiHttpClient c = new GreenApiHttpClient(new ObjectMapper(), "http://localhost:" + port, "1101", TOKEN,
                Duration.ofSeconds(2), Duration.ofSeconds(1));
        long t0 = System.nanoTime();

        assertThatThrownBy(() -> c.receive(1)).isInstanceOf(GatewayException.class).hasMessageNotContaining(TOKEN);
        assertThatThrownBy(() -> c.download("http://localhost:" + port + "/files/a.pdf", 1000))
                .isInstanceOf(GatewayException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(7));
    }

    @Test
    void missingCredentialsAreReportedWithoutNetwork() {
        GreenApiHttpClient c = new GreenApiHttpClient(new ObjectMapper(), "http://localhost:" + port, "1101", "");

        assertThat(c.isConfigured()).isFalse();
        assertThatThrownBy(() -> c.receive(5)).isInstanceOf(GatewayAuthException.class).hasMessageContaining("не заданы");
        assertThat(calls).isEmpty();
    }
}
