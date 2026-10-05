package com.vladoose.nir.integration.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramClientTest {

    static final String TOKEN = "123456:SECRET-TOKEN";
    final ObjectMapper json = new ObjectMapper();
    TelegramStubServer stub;

    @BeforeEach
    void up() throws Exception { stub = TelegramStubServer.start(0); }

    @AfterEach
    void down() { stub.close(); }

    TelegramClient client(String thread) {
        return new TelegramClient(new TelegramSettings(true, stub.url(), TOKEN, "-1001", thread), json);
    }

    @Test
    void sendsJsonToMailThread_returnsMessageId() throws Exception {
        stub.enqueue(TelegramStubServer.Reply.ok(555));

        long id = client("77").sendMail("Привет", true);

        assertThat(id).isEqualTo(555);
        TelegramStubServer.Request r = stub.requests().get(0);
        assertThat(r.path()).isEqualTo("/bot" + TOKEN + "/sendMessage");
        JsonNode b = json.readTree(r.body());
        assertThat(b.path("chat_id").asText()).isEqualTo("-1001");
        assertThat(b.path("message_thread_id").isNumber()).isTrue();
        assertThat(b.path("message_thread_id").asLong()).isEqualTo(77);
        assertThat(b.path("text").asText()).isEqualTo("Привет");
        assertThat(b.path("disable_notification").asBoolean()).isTrue();
        assertThat(b.path("link_preview_options").path("is_disabled").asBoolean()).isTrue();
    }

    @Test
    void loud_withoutThread_omitsOptionalFields() throws Exception {
        client("").sendMail("Текст", false);
        JsonNode b = json.readTree(stub.requests().get(0).body());
        assertThat(b.has("disable_notification")).isFalse();
        assertThat(b.has("message_thread_id")).isFalse();
    }

    @Test
    void rateLimited_carriesRetryAfter() {
        stub.enqueue(TelegramStubServer.Reply.tooMany(7));
        assertThatThrownBy(() -> client("77").sendMail("x", false))
                .isInstanceOfSatisfying(TelegramException.class, e -> {
                    assertThat(e.status()).isEqualTo(429);
                    assertThat(e.retryAfterSeconds()).isEqualTo(7);
                });
    }

    @Test
    void badRequest_messageHasDescription_noToken() {
        stub.enqueue(TelegramStubServer.Reply.error(400, "Bad Request: message thread not found"));
        assertThatThrownBy(() -> client("77").sendMail("x", false))
                .isInstanceOfSatisfying(TelegramException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("message thread not found").doesNotContain(TOKEN).doesNotContain("SECRET");
                });
    }

    @Test
    void serverErrorNonJson_noToken() {
        stub.enqueue(new TelegramStubServer.Reply(502, "<html>Bad Gateway</html>", 0));
        assertThatThrownBy(() -> client("77").sendMail("x", false))
                .hasMessageContaining("HTTP 502").hasMessageNotContaining("SECRET");
    }

    @Test
    void connectionRefused_noTokenNoUrl() {
        int port = stub.port();
        stub.close();
        TelegramClient c = new TelegramClient(new TelegramSettings(true, "http://127.0.0.1:" + port, TOKEN, "-1001", "77"), json);
        assertThatThrownBy(() -> c.sendMail("x", false))
                .isInstanceOf(TelegramException.class)
                .hasMessageNotContaining("SECRET").hasMessageNotContaining("127.0.0.1");
    }

    @Test
    void hangingBody_failsWithinDeadline() {
        stub.enqueue(TelegramStubServer.Reply.hang(3000));
        TelegramClient c = new TelegramClient(new TelegramSettings(true, stub.url(), TOKEN, "-1001", "77"), json,
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), Duration.ofMillis(500));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> c.sendMail("x", false)).isInstanceOf(TelegramException.class).hasMessageNotContaining("SECRET");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(2500));
    }

    @Test
    void brokenApiUrl_ownText_noToken() {
        TelegramClient c = new TelegramClient(new TelegramSettings(true, "http://bad host", TOKEN, "-1001", "77"), json);
        assertThatThrownBy(() -> c.sendMail("x", false))
                .isInstanceOf(TelegramException.class).hasMessageNotContaining("SECRET").hasMessageNotContaining("bad host");
    }

    @Test
    void nonNumericThread_ownText() {
        assertThatThrownBy(() -> client("abc").sendMail("x", false))
                .isInstanceOf(TelegramException.class).hasMessageContaining("TELEGRAM_MAIL_THREAD_ID");
    }

    @Test
    void settings_toStringHidesToken_configuredRules() {
        TelegramSettings s = new TelegramSettings(true, "https://api.telegram.org", TOKEN, "-1001", "77");
        assertThat(s.toString()).doesNotContain("SECRET").contains("-1001");
        assertThat(s.isConfigured()).isTrue();
        assertThat(new TelegramSettings(false, "x", TOKEN, "-1001", "").isConfigured()).isFalse();
        assertThat(new TelegramSettings(true, "x", "", "-1001", "").isConfigured()).isFalse();
        assertThat(new TelegramSettings(true, "x", TOKEN, " ", "").isConfigured()).isFalse();
    }
}
