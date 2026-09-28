package com.vladoose.nir.integration.greenapi;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath();
            String query = ex.getRequestURI().getRawQuery();
            calls.add(ex.getRequestMethod() + " " + path + (query != null ? "?" + query : ""));
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
                .isInstanceOf(GreenApiAuthException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining(TOKEN)
                .hasMessageNotContaining("waInstance");
        status = 403;
        assertThatThrownBy(() -> client().state()).isInstanceOf(GreenApiAuthException.class);
    }

    @Test
    void quota466IsQuotaError() {
        status = 466;
        body = "";
        assertThatThrownBy(() -> client().receive(5)).isInstanceOf(GreenApiQuotaException.class);
    }

    @Test
    void serverErrorKeepsStatusAndHidesToken() {
        status = 500;
        body = "";
        assertThatThrownBy(() -> client().delete(1))
                .isInstanceOfSatisfying(GreenApiException.class, e -> {
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
                .isInstanceOfSatisfying(GreenApiException.class, e -> {
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
                .isInstanceOf(GreenApiException.class).hasMessageContaining("https");
        assertThat(calls).isEmpty();
    }

    @Test
    void missingCredentialsAreReportedWithoutNetwork() {
        GreenApiHttpClient c = new GreenApiHttpClient(new ObjectMapper(), "http://localhost:" + port, "1101", "");

        assertThat(c.isConfigured()).isFalse();
        assertThatThrownBy(() -> c.receive(5)).isInstanceOf(GreenApiAuthException.class).hasMessageContaining("не заданы");
        assertThat(calls).isEmpty();
    }
}
