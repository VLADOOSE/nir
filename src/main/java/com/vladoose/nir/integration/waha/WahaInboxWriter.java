package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.entity.WhatsappInboxStatus;
import com.vladoose.nir.integration.whatsapp.WhatsappProviders;
import com.vladoose.nir.repository.WhatsappInboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static com.vladoose.nir.util.LeadText.trunc;

/**
 * Запись в очередь whatsapp_inbox (спека whatsapp-waha §4–5). Вставка — одним INSERT … ON CONFLICT DO NOTHING: повтор
 * WAHA (тот же X-Webhook-Request-Id) и то же сообщение из вебхука и догонки (ключ «fromMe_rawId») не дублируются, а
 * гонки «проверил — вставил» нет вовсе. JdbcTemplate, а не нативный запрос JPA: null-параметры (request_id у догонки)
 * Hibernate связывает с неопределённым типом.
 */
@Service
public class WahaInboxWriter {

    private static final String INSERT = """
            INSERT INTO whatsapp_inbox (provider, request_id, message_key, event, event_at, payload)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING""";

    private final JdbcTemplate jdbc;
    private final WhatsappInboxRepository repository;
    private final ObjectMapper objectMapper;

    public WahaInboxWriter(JdbcTemplate jdbc, WhatsappInboxRepository repository, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Событие вебхука; payload — сырое тело как пришло. false — такое уже лежит в очереди. */
    public boolean insert(JsonNode env, String requestId, String payload) {
        String event = env.path("event").asText("");
        String rid = requestId == null || requestId.isBlank() ? null : trunc(requestId.strip(), 100);
        return jdbc.update(INSERT, WhatsappProviders.WAHA, rid, WahaEventParser.messageKey(env),
                trunc(event.isEmpty() ? "?" : event, 40), capFuture(WahaEventParser.eventAt(env)), payload) == 1;
    }

    /**
     * Время события, но не позже «сейчас + 1 мин» — и в очереди, и у сообщения (WahaInboxSource.parse). Метка из
     * далёкого будущего (часы телефона, битое событие) иначе держала бы событие в PENDING вечно, а разобранное —
     * держало бы чат наверху с застывшим превью и усыпило бы догонку до этой даты.
     */
    static OffsetDateTime capFuture(OffsetDateTime at) {
        OffsetDateTime cap = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1);
        return at != null && at.isAfter(cap) ? cap : at;
    }

    /** Синтетическое событие догонки: тело — сериализованный конверт, request_id нет. */
    public boolean insert(JsonNode env) {
        try {
            return insert(env, null, objectMapper.writeValueAsString(env));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("событие догонки не сериализовано", e);
        }
    }

    @Transactional
    public void finish(long id, WhatsappInboxStatus status, String error) {
        repository.findById(id).ifPresent(e -> {
            e.setStatus(status);
            e.setLastError(trunc(error, 500));
            e.setProcessedAt(OffsetDateTime.now());
        });
    }

    /** Уборка (спека §5.4): DONE — старше doneBefore, DROPPED — старше droppedBefore; PENDING не трогаем никогда. */
    @Transactional
    public int cleanup(OffsetDateTime doneBefore, OffsetDateTime droppedBefore) {
        return repository.deleteProcessedBefore(WhatsappInboxStatus.DONE, doneBefore)
                + repository.deleteProcessedBefore(WhatsappInboxStatus.DROPPED, droppedBefore);
    }
}
