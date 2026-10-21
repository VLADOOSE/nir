package com.vladoose.nir.integration.telegram;

import com.fasterxml.jackson.databind.JsonNode;

/** Обновление Bot API, нужное кнопкам бота (спека 2026-10-21-tender-telegram-bot §8): нажатие или сообщение. */
public record BotUpdate(long updateId, Callback callback, Incoming message) {

    /** Нажатие кнопки: кто, в каком чате, на каком сообщении бота, данные кнопки. */
    public record Callback(String id, long fromId, long chatId, long messageId, String data) {}

    /** Сообщение: кто, где, текст; replyTo* — на какое сообщение и чьё это ответ (null — не ответ). */
    public record Incoming(long messageId, long chatId, long fromId, String text, Long replyToMessageId, Long replyToFromId) {}

    static BotUpdate of(JsonNode u) {
        long id = u.path("update_id").asLong();
        JsonNode cq = u.path("callback_query");
        if (cq.isObject()) {
            JsonNode msg = cq.path("message");
            return new BotUpdate(id, new Callback(cq.path("id").asText(), cq.path("from").path("id").asLong(),
                    msg.path("chat").path("id").asLong(), msg.path("message_id").asLong(), cq.path("data").asText("")), null);
        }
        JsonNode m = u.path("message");
        if (m.isObject()) {
            JsonNode r = m.path("reply_to_message");
            boolean reply = r.isObject();
            return new BotUpdate(id, null, new Incoming(m.path("message_id").asLong(), m.path("chat").path("id").asLong(),
                    m.path("from").path("id").asLong(), m.path("text").asText(m.path("caption").asText("")),
                    reply ? r.path("message_id").asLong() : null, reply ? r.path("from").path("id").asLong() : null));
        }
        return new BotUpdate(id, null, null);
    }
}
