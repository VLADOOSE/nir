package com.vladoose.nir.integration.whatsapp;

import com.vladoose.nir.dto.response.WhatsappStatusResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Состояние подключения WhatsApp для UI (спеки whatsapp-chats §7, whatsapp-waha §7). Пишут цикл приёма и источник
 * (поток «whatsapp-chats»), читают контроллеры — поля volatile, список предупреждений заменяется целиком.
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
    /** Предупреждения источника (Green-API — настройки инстанса); заменяются целиком. */
    private volatile List<String> sourceWarnings = List.of();
    /** Когда проход приёма в последний раз продвинулся (ответ шлюза, записанное уведомление). */
    private volatile long progressAt = System.currentTimeMillis();

    public void setState(String s) { state = s == null || s.isBlank() ? null : s; }

    /** «77000000001@c.us» / «77000000001:12@s.whatsapp.net» / «77000000001» → «77000000001»; пусто → null. */
    public void setNumber(String wid) {
        String w = wid == null ? "" : wid.strip();
        int at = w.indexOf('@');
        String user = at < 0 ? w : w.substring(0, at);
        int device = user.indexOf(':');
        String digits = device < 0 ? user : user.substring(0, device);
        number = digits.isEmpty() ? null : digits;
    }

    public void setSourceWarnings(List<String> warnings) { sourceWarnings = List.copyOf(warnings); }

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

    /** Пропущено «ядовитых» за сутки (счёт живёт с последнего пропуска). */
    public int recentDrops() {
        OffsetDateTime at = droppedAt;
        return at != null && at.isAfter(OffsetDateTime.now().minus(RECENT)) ? droppedCount : 0;
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
        List<String> w = new ArrayList<>(sourceWarnings);
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
