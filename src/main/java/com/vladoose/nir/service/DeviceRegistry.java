package com.vladoose.nir.service;

import com.vladoose.nir.entity.DeviceStatus;
import com.vladoose.nir.entity.TrustedDevice;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Допущенные устройства в памяти (спека device-gate §8): проверка калитки идёт на КАЖДЫЙ запрос к
 * ais.westmed.kz — в БД она не ходит. Бэкенд один, поэтому реестр и есть источник правды для проверки;
 * БД — для хранения и истории. Визиты копятся здесь и уходят в БД пачкой ({@link #drainVisits()}).
 */
@Component
public class DeviceRegistry {

    /** Отметка визита, ещё не записанная в БД; {@code null} — записывать нечего. */
    private record Visit(OffsetDateTime unsaved) {}

    private final ConcurrentHashMap<String, Visit> trusted = new ConcurrentHashMap<>();
    private final TrustedDeviceRepository repo;

    public DeviceRegistry(TrustedDeviceRepository repo) {
        this.repo = repo;
    }

    /** При старте — все допущенные из БД: перезапуск и деплой допуски не сбрасывают. */
    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        Map<String, Visit> fresh = new HashMap<>();
        for (TrustedDevice d : repo.findByStatus(DeviceStatus.TRUSTED)) fresh.put(d.getTokenHash(), new Visit(null));
        trusted.clear();
        trusted.putAll(fresh);
    }

    public boolean isTrusted(String hash) {
        return hash != null && trusted.containsKey(hash);
    }

    public void add(String hash) {
        trusted.put(hash, new Visit(null));
    }

    public void remove(String hash) {
        trusted.remove(hash);
    }

    /** Визит — только в памяти; в БД уходит пачкой. */
    public void touch(String hash, OffsetDateTime now) {
        trusted.computeIfPresent(hash, (h, v) -> new Visit(now));
    }

    /** Забрать накопленные визиты и пометить их записанными (если за это время не было нового визита). */
    public Map<String, OffsetDateTime> drainVisits() {
        Map<String, OffsetDateTime> out = new HashMap<>();
        for (Map.Entry<String, Visit> e : trusted.entrySet()) {
            OffsetDateTime seen = e.getValue().unsaved();
            if (seen == null) continue;
            out.put(e.getKey(), seen);
            trusted.computeIfPresent(e.getKey(), (h, cur) -> seen.equals(cur.unsaved()) ? new Visit(null) : cur);
        }
        return out;
    }
}
