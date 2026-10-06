package com.vladoose.nir.integration.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamHttpTest {

    static HttpServer server;
    static ExecutorService executor;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/stall", ex -> {          // заголовки пришли, тело встало
            ex.sendResponseHeaders(200, 1000);
            ex.getResponseBody().write(new byte[10]);
            ex.getResponseBody().flush();
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) { }
            ex.close();
        });
        server.createContext("/big", ex -> {
            byte[] b = new byte[2048];
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/ok", ex -> {
            byte[] b = "hi".getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "upstream-http-test");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        executor.shutdownNow();     // спящий /stall не держит набор тестов
    }

    URI uri(String p) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + p); }

    final HttpClient http = UpstreamHttp.newClient(HttpClient.Redirect.NEVER);

    @Test
    void stalledBody_endsByDeadline_retryable() {
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> UpstreamHttp.exchange(http, HttpRequest.newBuilder(uri("/stall")).build(),
                1_000_000, Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(UpstreamIoException.class, e -> {
                    assertThat(e.kind()).isEqualTo(UpstreamIoException.Kind.TIMEOUT);
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).isEqualTo("нет ответа за 1 с");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void bodyOverLimit_tooLarge_notRetryable() {
        assertThatThrownBy(() -> UpstreamHttp.exchange(http, HttpRequest.newBuilder(uri("/big")).build(),
                1024, Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(UpstreamIoException.class, e -> {
                    assertThat(e.kind()).isEqualTo(UpstreamIoException.Kind.TOO_LARGE);
                    assertThat(e.retryable()).isFalse();
                });
    }

    @Test
    void ok_returnsBody() throws Exception {
        assertThat(new String(UpstreamHttp.exchange(http, HttpRequest.newBuilder(uri("/ok")).build(),
                1024, Duration.ofSeconds(5)).body())).isEqualTo("hi");
    }

    @Test
    void refusedConnection_ioWithClassName() throws Exception {
        int closed;
        try (var s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        assertThatThrownBy(() -> UpstreamHttp.exchange(http,
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + closed + "/")).build(),
                1024, Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(UpstreamIoException.class, e -> {
                    assertThat(e.kind()).isEqualTo(UpstreamIoException.Kind.IO);
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).isEqualTo("ConnectException");
                });
    }
}
