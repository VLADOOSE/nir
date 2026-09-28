package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.repository.ChatMessageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Догонка пропущенного (спека whatsapp-waha §5.3): история WAHA от «самое позднее сообщение номера − 10 мин» ложится в
 * очередь синтетическими message.any с тем же ключом «fromMe_rawId» — принятое вебхуком не дублируется, а запись в чаты
 * и так отсекает дубль по (chat, external_id). Сообщений номера ещё нет (первая привязка) — догонки нет (решение 9).
 * Правки, удаления и звонки история не отдаёт — они приходят только вебхуком.
 */
@Component
public class WahaCatchUp {

    static final int PAGE = 100;
    /** 5000 сообщений за проход; остальное подберёт следующая догонка (через 10 мин). */
    static final int MAX_PAGES = 50;

    private final WahaClient client;
    private final WahaInboxWriter inbox;
    private final ChatMessageRepository messages;
    private final ObjectMapper objectMapper;
    private final long overlapMin;

    public WahaCatchUp(WahaClient client, WahaInboxWriter inbox, ChatMessageRepository messages, ObjectMapper objectMapper,
                       @Value("${chats.whatsapp.waha.catch-up-overlap-min:10}") long overlapMin) {
        this.client = client;
        this.inbox = inbox;
        this.messages = messages;
        this.objectMapper = objectMapper;
        this.overlapMin = overlapMin;
    }

    /** Сколько новых событий легло в очередь. account — номер сессии без «@c.us» (null — не привязан). */
    public int run(String session, String account) {
        if (account == null) return 0;
        OffsetDateTime latest = messages.findLatestSentAt(LeadChannel.WHATSAPP, account);
        if (latest == null) return 0;
        long from = latest.minusMinutes(overlapMin).toEpochSecond();
        int added = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<JsonNode> batch = client.history(session, from, PAGE, page * PAGE);
            for (JsonNode m : batch) {
                if (inbox.insert(envelope(session, account, m))) added++;
            }
            if (batch.size() < PAGE) break;
        }
        return added;
    }

    private ObjectNode envelope(String session, String account, JsonNode message) {
        ObjectNode env = objectMapper.createObjectNode();
        env.put("event", "message.any");
        env.put("session", session);
        env.put("timestamp", System.currentTimeMillis());
        env.put("origin", "catch-up");                  // в журнале очереди видно, что пришло не вебхуком
        env.set("me", objectMapper.createObjectNode().put("id", account + "@c.us"));
        env.set("payload", message);
        return env;
    }
}
