package com.vladoose.nir.integration.telegram;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Заглушка Bot API на JDK HttpServer: записывает запросы, отвечает заданными ответами по очереди (по умолчанию — 200 ok). */
public final class TelegramStubServer implements AutoCloseable {

    public record Request(String path, String body) {}

    public record Reply(int status, String body, long delayMs) {
        public static Reply ok(long messageId) {
            return new Reply(200, "{\"ok\":true,\"result\":{\"message_id\":" + messageId + "}}", 0);
        }
        public static Reply error(int status, String description) {
            return new Reply(status, "{\"ok\":false,\"error_code\":" + status + ",\"description\":\"" + description + "\"}", 0);
        }
        public static Reply tooMany(int retryAfter) {
            return new Reply(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after "
                    + retryAfter + "\",\"parameters\":{\"retry_after\":" + retryAfter + "}}", 0);
        }
        /** Заголовки уходят сразу, тело — через delayMs: проверка дедлайна на ВЕСЬ ответ. */
        public static Reply hang(long delayMs) {
            return new Reply(200, "{\"ok\":true,\"result\":{\"message_id\":1}}", delayMs);
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Deque<Reply> replies = new ConcurrentLinkedDeque<>();
    private final AtomicLong nextId = new AtomicLong(100);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private TelegramStubServer(HttpServer server) { this.server = server; }

    public static TelegramStubServer start(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        TelegramStubServer stub = new TelegramStubServer(s);
        s.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            stub.requests.add(new Request(ex.getRequestURI().getPath(), body));
            Reply r = stub.replies.poll();
            if (r == null) r = Reply.ok(stub.nextId.getAndIncrement());
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if (r.delayMs() > 0) {
                ex.sendResponseHeaders(r.status(), 0);          // chunked: заголовки ушли, тело — позже
                OutputStream os = ex.getResponseBody();
                os.flush();
                try { Thread.sleep(r.delayMs()); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                try { os.write(bytes); os.close(); } catch (IOException ignored) { }
                return;
            }
            ex.sendResponseHeaders(r.status(), bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });
        s.start();
        return stub;
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public int port() { return server.getAddress().getPort(); }
    public void enqueue(Reply... r) { replies.addAll(List.of(r)); }
    public List<Request> requests() { return requests; }

    /** Повторное закрытие — ничего не делает: тест может закрыть заглушку сам, а потом её закроет @AfterEach. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) server.stop(0);
    }
}
