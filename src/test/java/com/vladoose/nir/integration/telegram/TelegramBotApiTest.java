package com.vladoose.nir.integration.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.telegram.TelegramBotApi.Button;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Кнопки бота (спека 2026-10-21-tender-telegram-bot §7–8): формы запросов Bot API, multipart, обновления, ошибки. */
class TelegramBotApiTest {

    static final String TOKEN = "123456:SECRET-TOKEN";
    final ObjectMapper json = new ObjectMapper();
    TelegramStubServer stub;

    @BeforeEach void up() throws Exception { stub = TelegramStubServer.start(0); }
    @AfterEach void down() { stub.close(); }

    TelegramBotApi api() {
        return new TelegramBotApi(new TelegramSettings(true, stub.url(), TOKEN, "-1001", "77", "55", "95"), json);
    }

    JsonNode lastJson() throws Exception { return json.readTree(stub.requests().get(stub.requests().size() - 1).body()); }

    @Test
    void sendMessage_htmlButtonsReplyThread() throws Exception {
        stub.on("sendMessage", TelegramStubServer.Reply.ok(501));
        long id = api().sendMessage("<b>Пачка</b>", true, List.of(List.of(Button.cb("📋 Для ИИ", "d:7:ai"),
                Button.link("Открыть в АИС", "https://ais.westmed.kz/tenders?ids=1,2"))), 42L);
        assertThat(id).isEqualTo(501);
        JsonNode b = lastJson();
        assertThat(b.path("message_thread_id").asLong()).isEqualTo(95);
        assertThat(b.path("parse_mode").asText()).isEqualTo("HTML");
        assertThat(b.path("disable_notification").asBoolean()).isTrue();
        assertThat(b.path("reply_parameters").path("message_id").asLong()).isEqualTo(42);
        JsonNode row = b.path("reply_markup").path("inline_keyboard").get(0);
        assertThat(row.get(0).path("callback_data").asText()).isEqualTo("d:7:ai");
        assertThat(row.get(1).path("url").asText()).endsWith("ids=1,2");
    }

    @Test
    void forceReply_editKeyboard_delete_answer() throws Exception {
        TelegramBotApi api = api();
        api.sendForceReply("Пришлите блок", 9L, "Вставьте блок «Для АИС»");
        assertThat(lastJson().path("reply_markup").path("force_reply").asBoolean()).isTrue();
        api.editKeyboard(501, List.of(List.of(Button.cb("✅ 1", "l:3:x"))));
        assertThat(stub.requests().get(1).path()).endsWith("/editMessageReplyMarkup");
        stub.on("deleteMessage", TelegramStubServer.Reply.error(400, "Bad Request: message can't be deleted"));
        api.deleteMessage(501);   // старше 48 ч — не ошибка
        api.answerCallback("cb1", "Ваш Telegram ID 111", true);
        assertThat(lastJson().path("show_alert").asBoolean()).isTrue();
    }

    @Test
    void deleteIgnoresOnly400() {
        stub.on("deleteMessage", TelegramStubServer.Reply.error(500, "Internal Server Error"));
        assertThatThrownBy(() -> api().deleteMessage(501)).isInstanceOf(TelegramException.class);
    }

    @Test
    void sendDocument_multipartWithCyrillicName() {
        stub.on("sendDocument", TelegramStubServer.Reply.ok(600));
        long id = api().sendDocument("Для ИИ — 3 тендера.txt", "текст для ИИ".getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=utf-8", "Вставьте в Claude", List.of(List.of(Button.cb("✍️ Ответ ИИ", "d:7:ans"))), 501L);
        assertThat(id).isEqualTo(600);
        TelegramStubServer.Request r = stub.requests().get(0);
        assertThat(r.contentType()).startsWith("multipart/form-data; boundary=");
        assertThat(r.body()).contains("name=\"document\"; filename=\"Для ИИ — 3 тендера.txt\"")
                .contains("текст для ИИ").contains("name=\"caption\"").contains("d:7:ans").contains("name=\"message_thread_id\"");
    }

    @Test
    void sendPhoto_multipart() {
        api().sendPhoto("бланк.png", new byte[]{1, 2, 3}, "Письмо 1 из 1", List.of(), 501L);
        assertThat(stub.requests().get(0).path()).endsWith("/sendPhoto");
        assertThat(stub.requests().get(0).body()).contains("name=\"photo\"; filename=\"бланк.png\"");
    }

    @Test
    void getUpdates_parsesCallbackAndReply() throws Exception {
        stub.on("getUpdates", TelegramStubServer.Reply.json("[{\"update_id\":10,\"callback_query\":{\"id\":\"c1\",\"from\":{\"id\":111},"
                + "\"message\":{\"message_id\":501,\"chat\":{\"id\":-1001}},\"data\":\"d:7:ai\"}},{\"update_id\":11,\"message\":{"
                + "\"message_id\":502,\"chat\":{\"id\":-1001},\"from\":{\"id\":111},\"text\":\"L1 | идти | P2 | x\","
                + "\"reply_to_message\":{\"message_id\":501,\"from\":{\"id\":777}}}}]"));
        List<BotUpdate> us = api().getUpdates(10, 0);
        assertThat(us.get(0).callback()).isEqualTo(new BotUpdate.Callback("c1", 111, -1001, 501, "d:7:ai"));
        assertThat(us.get(1).message()).isEqualTo(new BotUpdate.Incoming(502, -1001, 111, "L1 | идти | P2 | x", 501L, 777L));
        JsonNode b = lastJson();
        assertThat(b.path("offset").asLong()).isEqualTo(10);
        assertThat(b.path("allowed_updates").toString()).contains("callback_query");
    }

    @Test
    void shortTooManyIsRetriedOnce() {
        stub.on("sendMessage", TelegramStubServer.Reply.tooMany(1), TelegramStubServer.Reply.ok(77));
        assertThat(api().sendMessage("x", true, null, null)).isEqualTo(77);
        assertThat(stub.requests()).hasSize(2);
    }

    @Test
    void errors_statusRetryAfter_noToken_botIdCached() {
        stub.on("getUpdates", TelegramStubServer.Reply.error(409, "Conflict: terminated by other getUpdates request; bot " + TOKEN));
        assertThatThrownBy(() -> api().getUpdates(0, 0)).isInstanceOf(TelegramException.class)
                .satisfies(e -> assertThat(((TelegramException) e).status()).isEqualTo(409))
                .hasMessageNotContaining("SECRET-TOKEN");
        stub.on("sendMessage", TelegramStubServer.Reply.tooMany(30));
        assertThatThrownBy(() -> api().sendMessage("x", true, null, null))
                .satisfies(e -> assertThat(((TelegramException) e).retryAfterSeconds()).isEqualTo(30));
        stub.on("getMe", TelegramStubServer.Reply.json("{\"id\":777,\"is_bot\":true}"));
        TelegramBotApi api = api();
        assertThat(api.botId()).isEqualTo(777);
        assertThat(api.botId()).isEqualTo(777);
        assertThat(stub.requests()).filteredOn(r -> r.path().endsWith("/getMe")).hasSize(1);
    }
}
