package com.vladoose.nir.integration.waha;

import com.vladoose.nir.integration.whatsapp.ChatKind;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.ParsedNotification;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Имена чатов для WAHA (спека whatsapp-waha §3). В событии WAHA есть только имя профиля отправителя (PushName), а
 * Green-API давал имя из контактов рабочего телефона — его берём из справочников WAHA: контакт (name — записная книжка,
 * pushname — профиль), тема группы, телефон за скрытым @lid. Кеш на 30 мин; сбой справочника сообщение не задерживает —
 * имя подтянется со следующим входящим.
 */
@Component
public class WahaChatNames {

    static final long TTL_MS = 30 * 60_000L;
    static final long FAILURE_TTL_MS = 2 * 60_000L;
    private static final int MAX_ENTRIES = 5_000;

    private final WahaClient client;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(String value, long until) {}

    public WahaChatNames(WahaClient client) { this.client = client; }

    public ParsedNotification.Message enrich(String session, ParsedNotification.Message m) {
        String name = m.kind() == ChatKind.GROUP
                ? lookup("group:" + m.chatId(), () -> client.groupSubject(session, m.chatId()))
                : lookup("contact:" + m.chatId(), () -> contactName(session, m.chatId()));
        String phone = m.phone();
        if (phone == null && m.kind() == ChatKind.PERSONAL_HIDDEN) {
            phone = lookup("lid:" + m.chatId(), () -> client.lidPhone(session, m.chatId()));
        }
        String chatName = name != null ? name : m.chatName();
        if (Objects.equals(chatName, m.chatName()) && Objects.equals(phone, m.phone())) return m;
        return m.withContact(chatName, phone);
    }

    private String contactName(String session, String chatId) {
        WahaContact c = client.contact(session, chatId);
        if (c == null) return null;
        if (c.name() != null && !c.name().isBlank()) return c.name().strip();
        return c.pushname() != null && !c.pushname().isBlank() ? c.pushname().strip() : null;
    }

    private String lookup(String key, Supplier<String> load) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && c.until() > now) return c.value();
        String value;
        long ttl = TTL_MS;
        try {
            value = load.get();
        } catch (GatewayException e) {
            value = c == null ? null : c.value();     // прежнее имя лучше, чем никакое
            ttl = FAILURE_TTL_MS;
        }
        if (cache.size() > MAX_ENTRIES) cache.clear();
        cache.put(key, new Cached(value, now + ttl));
        return value;
    }
}
