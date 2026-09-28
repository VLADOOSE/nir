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
    private volatile List<String> settingsWarnings = List.of();

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

    public void messageDropped() { droppedAt = OffsetDateTime.now(); }

    public void setLastError(String e) { lastError = e; }

    public String lastError() { return lastError; }

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
        if (droppedAt != null && droppedAt.isAfter(cutoff)) w.add(MESSAGE_DROPPED);
        r.setWarnings(w);
        return r;
    }
}
