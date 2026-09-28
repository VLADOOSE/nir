package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.entity.WhatsappInboxStatus;
import com.vladoose.nir.repository.WhatsappInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Приём вебхука WAHA через настоящую цепочку фильтров (спека whatsapp-waha §5.1, §10). Ключ подписи — системное
 * свойство теста из build.gradle: так тест живёт в ОБЩЕМ контексте, без @TestPropertySource (каждый особый контекст —
 * ещё 10 соединений nirdb, CLAUDE.md §14).
 */
@SpringBootTest
@Transactional
class WahaWebhookTest {

    static final String KEY = "test-hmac-key";
    static final String CLIENT = "77011234567@c.us";
    static final long T = 1_790_000_000L;

    @Autowired WebApplicationContext wac;
    @Autowired WahaInboxWriter writer;
    @Autowired WhatsappInboxRepository repository;
    @Autowired ObjectMapper objectMapper;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    static String raw() { return "3EB0" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(); }

    static byte[] bytes(ObjectNode env) { return env.toString().getBytes(StandardCharsets.UTF_8); }

    private ResultActions post(byte[] body, String signature, String requestId) throws Exception {
        MockHttpServletRequestBuilder r = MockMvcRequestBuilders.post(WahaWebhookController.PATH)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) r.header("X-Webhook-Hmac", signature).header("X-Webhook-Hmac-Algorithm", "sha512");
        if (requestId != null) r.header("X-Webhook-Request-Id", requestId);
        return mvc.perform(r);
    }

    /** Без входа в АИС (его зовёт WAHA внутри сети docker) — но только с верной подписью. */
    @Test
    void signedEventIsQueuedWithoutLogin() throws Exception {
        String rawId = raw();
        byte[] body = bytes(WahaJson.incomingText(CLIENT, "Айгерим", rawId, T, "Здравствуйте"));
        String rid = "req-" + rawId;

        post(body, WahaSignature.sign(body, KEY), rid).andExpect(status().isOk());

        assertThat(repository.findByRequestId(rid)).singleElement().satisfies(e -> {
            assertThat(e.getProvider()).isEqualTo("waha");
            assertThat(e.getEvent()).isEqualTo("message.any");
            assertThat(e.getMessageKey()).isEqualTo("false_" + rawId);
            assertThat(e.getStatus()).isEqualTo(WhatsappInboxStatus.PENDING);
            assertThat(e.getEventAt().toEpochSecond()).isEqualTo(T);
            assertThat(e.getPayload()).isEqualTo(new String(body, StandardCharsets.UTF_8));
        });
    }

    @Test
    void missingOrForeignSignatureIs401AndNothingQueued() throws Exception {
        String rawId = raw();
        byte[] body = bytes(WahaJson.incomingText(CLIENT, "Айгерим", rawId, T, "x"));
        byte[] tampered = bytes(WahaJson.incomingText(CLIENT, "Айгерим", rawId, T, "y"));

        post(body, null, "req-a-" + rawId).andExpect(status().isUnauthorized());
        post(body, WahaSignature.sign(body, "чужой-ключ"), "req-b-" + rawId).andExpect(status().isUnauthorized());
        post(tampered, WahaSignature.sign(body, KEY), "req-c-" + rawId).andExpect(status().isUnauthorized());

        assertThat(repository.findByMessageKey("false_" + rawId)).isEmpty();
    }

    /** Повтор WAHA приходит с тем же X-Webhook-Request-Id. */
    @Test
    void wahaRetryIsStoredOnce() throws Exception {
        byte[] body = bytes(WahaJson.sessionStatus("WORKING"));
        String sig = WahaSignature.sign(body, KEY);
        String rid = "req-" + raw();

        post(body, sig, rid).andExpect(status().isOk());
        post(body, sig, rid).andExpect(status().isOk());

        assertThat(repository.findByRequestId(rid)).singleElement()
                .satisfies(e -> assertThat(e.getMessageKey()).isNull());
    }

    /** Сообщение, пришедшее и вебхуком, и догонкой (другой конверт, без request id), лежит в очереди один раз. */
    @Test
    void sameMessageFromWebhookAndCatchUpIsStoredOnce() throws Exception {
        String rawId = raw();
        ObjectNode env = WahaJson.incomingText(CLIENT, "Айгерим", rawId, T, "x");
        byte[] body = bytes(env);
        post(body, WahaSignature.sign(body, KEY), "req-" + rawId).andExpect(status().isOk());

        ObjectNode fromHistory = WahaJson.envelope("message.any", env.get("payload").deepCopy());

        assertThat(writer.insert(fromHistory)).isFalse();
        assertThat(repository.findByMessageKey("false_" + rawId)).hasSize(1);
    }

    /** Подписано нашим ключом, но не JSON: 4xx WAHA повторяла бы 12 раз впустую — принимаем и пропускаем. */
    @Test
    void signedNonJsonIsAcknowledgedAndSkipped() throws Exception {
        byte[] body = "не json".getBytes(StandardCharsets.UTF_8);
        String rid = "req-" + raw();

        post(body, WahaSignature.sign(body, KEY), rid).andExpect(status().isOk());

        assertThat(repository.findByRequestId(rid)).isEmpty();
    }

    /** БД недоступна — 503: WAHA повторит (её повторы живут в памяти до ~4,5 ч). */
    @Test
    void databaseOutageIs503() {
        WahaInboxWriter down = new WahaInboxWriter(null, null, objectMapper) {
            @Override
            public boolean insert(JsonNode env, String requestId, String payload) {
                throw new CannotCreateTransactionException("нет соединения с базой");
            }
        };
        WahaWebhookController controller = new WahaWebhookController(down, objectMapper, KEY);
        byte[] body = bytes(WahaJson.sessionStatus("WORKING"));

        assertThat(controller.receive(WahaSignature.sign(body, KEY), "req-x", body).getStatusCode().value()).isEqualTo(503);
    }
}
