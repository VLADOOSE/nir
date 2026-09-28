package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.vladoose.nir.entity.WhatsappInboxStatus;
import com.vladoose.nir.integration.whatsapp.*;
import com.vladoose.nir.repository.WhatsappInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * WAHA как источник (спека whatsapp-waha §3, §5): очередь — таблица whatsapp_inbox, которую наполняет вебхук.
 * Событие берётся, «отлежавшись» settle-ms (правка, пришедшая чуть раньше оригинала, встанет после него), и
 * подтверждается как DONE или DROPPED с причиной. Раз в минуту — статус сессии; WAHA не отвечает — красная строка,
 * но уже принятое разбирается дальше. Все методы, кроме name/isConfigured, зовёт поток приёма.
 */
@Component
public class WahaInboxSource implements WhatsappSource {

    private static final Logger log = LoggerFactory.getLogger(WahaInboxSource.class);

    private final WhatsappInboxRepository repository;
    private final WahaInboxWriter inbox;
    private final WahaClient client;
    private final WahaSessionManager sessions;
    private final WahaChatNames names;
    private final ObjectMapper objectMapper;
    private final String hmacKey;
    private final long settleMs;
    private final long statusRefreshMs;
    private long nextStatusCheck;
    private String loggedSourceError;

    public WahaInboxSource(WhatsappInboxRepository repository, WahaInboxWriter inbox, WahaClient client,
                           WahaSessionManager sessions, WahaChatNames names, ObjectMapper objectMapper,
                           @Value("${chats.whatsapp.waha.hmac-key:}") String hmacKey,
                           @Value("${chats.whatsapp.waha.settle-ms:3000}") long settleMs,
                           @Value("${chats.whatsapp.waha.status-refresh-ms:60000}") long statusRefreshMs) {
        this.repository = repository;
        this.inbox = inbox;
        this.client = client;
        this.sessions = sessions;
        this.names = names;
        this.objectMapper = objectMapper;
        this.hmacKey = hmacKey;
        this.settleMs = settleMs;
        this.statusRefreshMs = statusRefreshMs;
    }

    @Override public String name() { return WhatsappProviders.WAHA; }

    @Override public boolean isConfigured() { return client.isConfigured() && hmacKey != null && !hmacKey.isEmpty(); }

    @Override public String configHint() {
        return "не заданы ключи шлюза WAHA (WHATSAPP_WAHA_API_KEY / WHATSAPP_WAHA_HMAC_KEY)";
    }

    @Override public String waitingNote() { return "сообщения ждут в очереди АИС"; }

    @Override
    public void housekeeping(WhatsappStatusHolder status) {
        long now = System.currentTimeMillis();
        if (now < nextStatusCheck) return;
        nextStatusCheck = now + statusRefreshMs;
        refreshSession(status);
    }

    private void refreshSession(WhatsappStatusHolder status) {
        try {
            sessions.refresh(status);
            status.setSourceError(null);
            loggedSourceError = null;
        } catch (GatewayException e) {
            // принятое вебхуком разбирается и без WAHA: цикл не останавливаем, только красная строка
            status.setSourceError(e.getMessage() + " — принятые сообщения разбираются, файлы и догонка ждут");
            if (!Objects.equals(loggedSourceError, e.getMessage())) {
                log.warn("WhatsApp: {}", e.getMessage());
                loggedSourceError = e.getMessage();
            }
        }
    }

    @Override
    public WhatsappNotification next() {
        OffsetDateTime settled = OffsetDateTime.now().minus(Duration.ofMillis(settleMs));
        return repository.findNextPending(WhatsappProviders.WAHA, settled)
                .map(e -> new WhatsappNotification(e.getId(), read(e.getPayload())))
                .orElse(null);
    }

    private JsonNode read(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (IOException e) {
            return MissingNode.getInstance();     // вебхук кладёт только JSON; битое — парсер пропустит
        }
    }

    @Override
    public ParsedNotification parse(WhatsappNotification n) {
        ParsedNotification p = WahaEventParser.parse(n.body(), sessions.account());
        if (p instanceof ParsedNotification.Message m) return names.enrich(sessions.session(), m);
        return p;
    }

    @Override
    public void ack(WhatsappNotification n, String droppedReason) {
        inbox.finish(n.id(), droppedReason == null ? WhatsappInboxStatus.DONE : WhatsappInboxStatus.DROPPED, droppedReason);
    }

    /** Файл: WAHA сама не качает (WAHA_EVENTS_DOWNLOAD_MEDIA=false) — просим сообщение с файлом, берём по media.url. */
    @Override
    public byte[] download(FileRef ref, long maxBytes) {
        WahaEventParser.MessageId id = WahaEventParser.MessageId.parse(ref.locator());
        if (id == null) throw new GatewayException(0, "WAHA: у файла нет id сообщения");
        JsonNode msg = client.message(sessions.session(), id.chatId(), ref.locator(), true);
        JsonNode url = msg == null ? null : msg.path("media").path("url");
        if (url == null || !url.isTextual() || url.asText().isBlank()) {
            throw new GatewayException(0, "WAHA: файл сообщения не скачался");
        }
        return client.downloadFile(url.asText(), maxBytes);
    }
}
