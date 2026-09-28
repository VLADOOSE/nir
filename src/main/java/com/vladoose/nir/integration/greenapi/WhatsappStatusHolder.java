package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.dto.response.WhatsappStatusResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Состояние подключения WhatsApp для UI (спека §7). Пишут цикл приёма и планировщик (поток «whatsapp-chats»),
 * читает контроллер — поля volatile, список предупреждений заменяется целиком.
 */
@Component
public class WhatsappStatusHolder {

    public static final String WEBHOOK_URL_SET = "WEBHOOK_URL_SET";
    public static final String INCOMING_OFF = "INCOMING_OFF";
    public static final String OUTGOING_PHONE_OFF = "OUTGOING_PHONE_OFF";
    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String MESSAGE_DROPPED = "MESSAGE_DROPPED";

    /** Лимит тарифа и пропущенное сообщение показываем сутки: оба — события, а не состояние. */
    private static final Duration RECENT = Duration.ofHours(24);

    private volatile String state;
    private volatile String number;
    private volatile OffsetDateTime lastMessageAt;
    private volatile String lastError;
    private volatile OffsetDateTime quotaExceededAt;
    private volatile OffsetDateTime droppedAt;
    private volatile int droppedCount;
    private volatile List<String> settingsWarnings = List.of();
    /** Когда проход приёма в последний раз продвинулся (ответ Green-API, записанное уведомление). */
    private volatile long progressAt = System.currentTimeMillis();

    public void setState(String s) { state = s == null || s.isBlank() ? null : s; }

    public void settingsChecked(GreenApiSettings s) {
        String wid = s.wid() == null ? "" : s.wid().strip();
        int at = wid.indexOf('@');
        String digits = at < 0 ? wid : wid.substring(0, at);
        number = digits.isEmpty() ? null : digits;
        List<String> w = new ArrayList<>();
        if (s.webhookUrl() != null && !s.webhookUrl().isBlank()) w.add(WEBHOOK_URL_SET);
        if (!s.incomingWebhook()) w.add(INCOMING_OFF);
        if (!s.outgoingMessageWebhook()) w.add(OUTGOING_PHONE_OFF);
        settingsWarnings = List.copyOf(w);
    }

    public void messageSeen(OffsetDateTime at) {
        if (at != null && (lastMessageAt == null || at.isAfter(lastMessageAt))) lastMessageAt = at;
    }

    public void quotaExceeded() { quotaExceededAt = OffsetDateTime.now(); }

    /** Пишет один поток приёма — счёт без гонок; через сутки тишины начинается заново. */
    public void messageDropped() {
        OffsetDateTime now = OffsetDateTime.now();
        if (droppedAt == null || droppedAt.isBefore(now.minus(RECENT))) droppedCount = 0;
        droppedCount++;
        droppedAt = now;
    }

    public void setLastError(String e) { lastError = e; }

    public String lastError() { return lastError; }

    public void progress() { progressAt = System.currentTimeMillis(); }

    public long sinceProgressMs() { return System.currentTimeMillis() - progressAt; }

    public WhatsappStatusResponse snapshot(boolean enabled, boolean configured) {
        WhatsappStatusResponse r = new WhatsappStatusResponse();
        r.setEnabled(enabled);
        r.setConfigured(configured);
        r.setState(state);
        r.setNumber(number);
        r.setLastMessageAt(lastMessageAt);
        r.setLastError(lastError);
        List<String> w = new ArrayList<>(settingsWarnings);
        OffsetDateTime cutoff = OffsetDateTime.now().minus(RECENT);
        if (quotaExceededAt != null && quotaExceededAt.isAfter(cutoff)) w.add(QUOTA_EXCEEDED);
        if (droppedAt != null && droppedAt.isAfter(cutoff)) {
            w.add(MESSAGE_DROPPED);
            r.setDroppedCount(droppedCount);
        }
        r.setWarnings(w);
        return r;
    }
}
