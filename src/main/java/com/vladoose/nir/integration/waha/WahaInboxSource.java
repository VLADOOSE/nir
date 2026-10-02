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
import java.util.List;
import java.util.Objects;

/**
 * WAHA как источник (спека whatsapp-waha §3, §5): очередь — таблица whatsapp_inbox, которую наполняет вебхук.
 * Событие берётся, «отлежавшись» settle-ms (правка, пришедшая чуть раньше оригинала, встанет после него), и
 * подтверждается как DONE или DROPPED с причиной. Обслуживание: раз в минуту — статус сессии; при старте, при переходе
 * в WORKING и раз в 10 мин — догонка по истории; раз в сутки — уборка очереди. WAHA не отвечает — красная строка, но
 * уже принятое разбирается дальше. Все методы, кроме name/isConfigured, зовёт поток приёма.
 */
@Component
public class WahaInboxSource implements WhatsappSource {

    private static final Logger log = LoggerFactory.getLogger(WahaInboxSource.class);
    static final long CLEANUP_EVERY_MS = 24 * 3600_000L;

    private final WhatsappInboxRepository repository;
    private final WahaInboxWriter inbox;
    private final WahaClient client;
    private final WahaSessionManager sessions;
    private final WahaChatNames names;
    private final WahaCatchUp catchUp;
    private final ObjectMapper objectMapper;
    private final String hmacKey;
    private final long settleMs;
    private final long statusRefreshMs;
    private final long catchUpMs;
    private final int doneDays;
    private final int droppedDays;
    private long nextStatusCheck;
    private long nextCatchUp;
    private long nextCleanup;
    private boolean catchUpDue;
    private boolean catchUpFailed;
    private String loggedSourceError;

    public WahaInboxSource(WhatsappInboxRepository repository, WahaInboxWriter inbox, WahaClient client,
                           WahaSessionManager sessions, WahaChatNames names, WahaCatchUp catchUp, ObjectMapper objectMapper,
                           @Value("${chats.whatsapp.waha.hmac-key:}") String hmacKey,
                           @Value("${chats.whatsapp.waha.settle-ms:3000}") long settleMs,
                           @Value("${chats.whatsapp.waha.status-refresh-ms:60000}") long statusRefreshMs,
                           @Value("${chats.whatsapp.waha.catch-up-ms:600000}") long catchUpMs,
                           @Value("${chats.whatsapp.waha.inbox-done-days:7}") int doneDays,
                           @Value("${chats.whatsapp.waha.inbox-dropped-days:30}") int droppedDays) {
        this.repository = repository;
        this.inbox = inbox;
        this.client = client;
        this.sessions = sessions;
        this.names = names;
        this.catchUp = catchUp;
        this.objectMapper = objectMapper;
        this.hmacKey = hmacKey;
        this.settleMs = settleMs;
        this.statusRefreshMs = statusRefreshMs;
        this.catchUpMs = catchUpMs;
        this.doneDays = doneDays;
        this.droppedDays = droppedDays;
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
        if (now >= nextStatusCheck) {
            nextStatusCheck = now + statusRefreshMs;
            refreshSession(status);
        }
        if ((catchUpDue || now >= nextCatchUp) && WahaSessionManager.WORKING.equals(status.state())) {
            catchUpDue = false;
            nextCatchUp = now + catchUpMs;
            runCatchUp(status);
        }
        if (now >= nextCleanup) {
            nextCleanup = now + CLEANUP_EVERY_MS;
            OffsetDateTime t = OffsetDateTime.now();
            int removed = inbox.cleanup(t.minusDays(doneDays), t.minusDays(droppedDays));
            if (removed > 0) log.info("WhatsApp: из очереди убрано разобранных событий: {}", removed);
        }
    }

    private void refreshSession(WhatsappStatusHolder status) {
        try {
            if (sessions.refresh(status)) catchUpDue = true;
            status.setSourceError(null);
            loggedSourceError = null;
        } catch (GatewayException e) {
            // принятое вебхуком разбирается и без WAHA: цикл не останавливаем, только красная строка. Файл получает три
            // попытки (~1 мин), потом сообщение пишется без него — «ждут» здесь было бы неправдой
            status.setSourceError(e.getMessage() + " — принятые сообщения разбираются, но файлы к ним могут не скачаться"
                    + " (останутся в телефоне); догонка — когда WAHA ответит");
            if (!Objects.equals(loggedSourceError, e.getMessage())) {
                log.warn("WhatsApp: {}", e.getMessage());
                loggedSourceError = e.getMessage();
            }
        }
    }

    private void runCatchUp(WhatsappStatusHolder status) {
        try {
            int added = catchUp.run(sessions.session(), sessions.account(), status::progress);
            if (added > 0) log.info("WhatsApp: догонка добавила в очередь сообщений: {}", added);
            if (catchUpFailed) {
                status.setSourceWarnings(List.of());
                catchUpFailed = false;
            }
        } catch (GatewayException e) {
            // приём вебхуков догонка не останавливает (спека §5.3): предупреждение и повтор в следующий раз
            status.setSourceWarnings(List.of(WhatsappStatusHolder.CATCH_UP_FAILED));
            if (!catchUpFailed) log.warn("WhatsApp: догонка не удалась — {}", e.getMessage());
            catchUpFailed = true;
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
        if (p instanceof ParsedNotification.State s) {
            // событие лишь торопит опрос: статус и номер берём из сессии ОДНИМ чтением на ближайшем проходе (~1 с),
            // иначе строка на миг показывала «подключён» без номера (поймано живьём)
            nextStatusCheck = 0;
            if (WahaSessionManager.WORKING.equals(s.state())) catchUpDue = true;
            return new ParsedNotification.Skip("статус сессии — из опроса сессии");
        }
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
