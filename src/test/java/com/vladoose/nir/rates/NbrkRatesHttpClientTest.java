package com.vladoose.nir.rates;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.http.UpstreamHttp;
import com.vladoose.nir.integration.nbrk.NbrkRate;
import com.vladoose.nir.integration.nbrk.NbrkRatesHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NbrkRatesHttpClientTest {

    static HttpServer server;
    static final AtomicReference<String> query = new AtomicReference<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "nbrk-stub"); t.setDaemon(true); return t; }));
        server.createContext("/ok/rss/get_rates.cfm", ex -> {
            query.set(ex.getRequestURI().getQuery());
            send(ex, 200, NbrkRatesParserTest.XML);
        });
        server.createContext("/e500/rss/get_rates.cfm", ex -> send(ex, 500, "boom"));
        server.createContext("/stall/rss/get_rates.cfm", ex -> {
            ex.sendResponseHeaders(200, 1000);
            ex.getResponseBody().write("<rates>".getBytes(StandardCharsets.UTF_8));
            ex.getResponseBody().flush();
            try { Thread.sleep(5_000); } catch (InterruptedException ignored) { }
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() { server.stop(0); }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static NbrkRatesHttpClient client(String path) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/" + path;
        return new NbrkRatesHttpClient(base, UpstreamHttp.newClient(HttpClient.Redirect.NEVER), Duration.ofMillis(800));
    }

    @Test
    void fetch_byDate() {
        List<NbrkRate> r = client("ok").fetch(LocalDate.of(2026, 10, 22));
        assertThat(query.get()).isEqualTo("fdate=22.10.2026");
        assertThat(r).extracting(NbrkRate::currency).contains("RUB", "USD");
    }

    @Test
    void http500_andStall_areUpstreamErrors() {
        assertThatThrownBy(() -> client("e500").fetch(LocalDate.of(2026, 10, 22)))
                .isInstanceOf(UpstreamException.class).hasMessage("Нацбанк РК: HTTP 500");
        assertThatThrownBy(() -> client("stall").fetch(LocalDate.of(2026, 10, 22)))
                .isInstanceOf(UpstreamException.class).hasMessageContaining("нет ответа");
    }
}
