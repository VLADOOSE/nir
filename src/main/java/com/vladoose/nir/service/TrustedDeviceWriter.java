package com.vladoose.nir.service;

import com.vladoose.nir.entity.DeviceStatus;
import com.vladoose.nir.entity.TrustedDevice;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Запись в {@code trusted_device} — каждый метод в своей транзакции. Отдельный бин, а не методы сервиса:
 * {@link DeviceGateService} трогает реестр в памяти только после возврата отсюда, то есть после коммита —
 * провалившийся коммит реестр не меняет. (Колбэк «после коммита» не подошёл бы ещё и потому, что
 * {@code @Transactional}-тесты не коммитят — тесты не увидели бы допуска.)
 */
@Service
public class TrustedDeviceWriter {

    private final TrustedDeviceRepository repo;

    public TrustedDeviceWriter(TrustedDeviceRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public TrustedDevice createPending(String tokenHash, String code, String name, String userAgent, String ip,
                                       OffsetDateTime now) {
        return repo.save(TrustedDevice.builder().tokenHash(tokenHash).status(DeviceStatus.PENDING).code(code)
                .requesterName(name).userAgent(userAgent).ip(ip).requestedAt(now).build());
    }

    /** @param staleBefore запросы, поданные не позже этого момента, считаются истёкшими */
    @Transactional
    public TrustedDevice approve(Long id, String label, String admin, OffsetDateTime now, OffsetDateTime staleBefore) {
        TrustedDevice d = pendingOrConflict(id, staleBefore);
        d.setStatus(DeviceStatus.TRUSTED);
        d.setLabel(label != null ? label : d.getRequesterName());
        d.setDecidedAt(now);
        d.setDecidedBy(admin);
        return d;
    }

    @Transactional
    public TrustedDevice reject(Long id, String admin, OffsetDateTime now, OffsetDateTime staleBefore) {
        TrustedDevice d = pendingOrConflict(id, staleBefore);
        d.setStatus(DeviceStatus.REJECTED);
        d.setDecidedAt(now);
        d.setDecidedBy(admin);
        return d;
    }

    @Transactional
    public TrustedDevice revoke(Long id, String admin, OffsetDateTime now) {
        TrustedDevice d = repo.findById(id).orElseThrow(() -> new NotFoundException("Устройство не найдено"));
        if (d.getStatus() != DeviceStatus.TRUSTED) throw new ConflictException("Устройство не допущено — отзывать нечего");
        d.setStatus(DeviceStatus.REVOKED);
        d.setDecidedAt(now);
        d.setDecidedBy(admin);
        return d;
    }

    @Transactional
    public int expirePending(OffsetDateTime staleBefore) {
        return repo.moveRequestedBefore(DeviceStatus.PENDING, DeviceStatus.EXPIRED, staleBefore);
    }

    /** @return хеши выпавших устройств — их надо убрать из реестра */
    @Transactional
    public List<String> expireIdle(OffsetDateTime idleBefore) {
        List<String> gone = new ArrayList<>();
        for (TrustedDevice d : repo.findByStatus(DeviceStatus.TRUSTED)) {
            OffsetDateTime last = latest(d.getLastSeenAt(), d.getDecidedAt());
            if (last == null || last.isBefore(idleBefore)) {
                d.setStatus(DeviceStatus.EXPIRED);
                gone.add(d.getTokenHash());
            }
        }
        return gone;
    }

    @Transactional
    public void saveVisits(Map<String, OffsetDateTime> visits) {
        visits.forEach((hash, seen) -> repo.markSeen(hash, seen, DeviceStatus.TRUSTED));
    }

    private TrustedDevice pendingOrConflict(Long id, OffsetDateTime staleBefore) {
        TrustedDevice d = repo.findById(id).orElseThrow(() -> new NotFoundException("Запрос не найден"));
        if (d.getStatus() != DeviceStatus.PENDING) throw new ConflictException("Запрос уже не ожидает решения");
        if (!d.getRequestedAt().isAfter(staleBefore)) {
            throw new ConflictException("Запрос истёк — попросите запросить доступ заново");
        }
        return d;
    }

    private static OffsetDateTime latest(OffsetDateTime a, OffsetDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
