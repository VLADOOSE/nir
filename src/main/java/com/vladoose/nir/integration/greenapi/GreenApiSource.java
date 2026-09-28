package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.integration.whatsapp.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Green-API как источник (спека whatsapp-chats-green-api): очередь сервиса, long-poll; раз в 5 мин — состояние
 * инстанса, раз в час — настройки (номер, адрес вебхука, флаги уведомлений). Поведение то же, что до выделения
 * источника (спека whatsapp-waha §1, решение 2 — Green-API остаётся запасным).
 */
@Component
public class GreenApiSource implements WhatsappSource {

    public static final String NAME = WhatsappProviders.GREENAPI;

    private final GreenApiClient client;
    private final int receiveTimeoutSec;
    private final long stateRefreshMs;
    private final long settingsRefreshMs;
    private long nextStateCheck;
    private long nextSettingsCheck;

    @Autowired
    public GreenApiSource(GreenApiClient client,
                          @Value("${chats.whatsapp.receive-timeout-s:20}") int receiveTimeoutSec,
                          @Value("${chats.whatsapp.state-refresh-ms:300000}") long stateRefreshMs,
                          @Value("${chats.whatsapp.settings-refresh-ms:3600000}") long settingsRefreshMs) {
        this.client = client;
        this.receiveTimeoutSec = receiveTimeoutSec;
        this.stateRefreshMs = stateRefreshMs;
        this.settingsRefreshMs = settingsRefreshMs;
    }

    @Override public String name() { return NAME; }

    @Override public boolean isConfigured() { return client.isConfigured(); }

    @Override public String configHint() {
        return "не заданы учётные данные Green-API (WHATSAPP_API_URL / WHATSAPP_ID_INSTANCE / WHATSAPP_API_TOKEN)";
    }

    @Override public String waitingNote() { return "сообщения ждут в очереди Green-API до суток"; }

    @Override
    public void housekeeping(WhatsappStatusHolder status) {
        long now = System.currentTimeMillis();
        if (now >= nextSettingsCheck) {
            GreenApiSettings s = client.settings();
            status.setNumber(s.wid());
            status.setSourceWarnings(warnings(s));
            nextSettingsCheck = now + settingsRefreshMs;
        }
        if (now >= nextStateCheck) {
            status.setState(client.state());
            nextStateCheck = now + stateRefreshMs;
        }
    }

    /** Без этих настроек приём молча неполный (спека whatsapp-chats §7). */
    static List<String> warnings(GreenApiSettings s) {
        List<String> w = new ArrayList<>();
        if (s.webhookUrl() != null && !s.webhookUrl().isBlank()) w.add(WhatsappStatusHolder.WEBHOOK_URL_SET);
        if (!s.incomingWebhook()) w.add(WhatsappStatusHolder.INCOMING_OFF);
        if (!s.outgoingMessageWebhook()) w.add(WhatsappStatusHolder.OUTGOING_PHONE_OFF);
        return w;
    }

    @Override
    public WhatsappNotification next() {
        GreenApiReceived r = client.receive(receiveTimeoutSec);
        return r == null ? null : new WhatsappNotification(r.receiptId(), r.body());
    }

    @Override
    public ParsedNotification parse(WhatsappNotification n) { return GreenApiNotificationParser.parse(n.body()); }

    /** Green-API различий «принято/пропущено» не знает: в обоих случаях уведомление удаляется из его очереди. */
    @Override
    public void ack(WhatsappNotification n, String droppedReason) { client.delete(n.id()); }

    @Override
    public byte[] download(FileRef ref, long maxBytes) { return client.download(ref.locator(), maxBytes); }
}
