package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.DeviceListResponse;
import com.vladoose.nir.dto.response.DeviceResponse;
import com.vladoose.nir.entity.DeviceStatus;
import com.vladoose.nir.entity.TrustedDevice;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.TooManyRequestsException;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import com.vladoose.nir.util.DeviceTokens;
import com.vladoose.nir.util.UserAgentSummary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Калитка ais.westmed.kz: запрос доступа с устройства, решение админа, проверка на каждый запрос
 * (спека docs/superpowers/specs/2026-09-28-device-gate-design.md). Изменения БД — в {@link TrustedDeviceWriter}
 * (своя транзакция на метод); реестр в памяти обновляется после его возврата — то есть после коммита.
 */
@Service
public class DeviceGateService {

    public static final Duration PENDING_TTL = Duration.ofMinutes(15);
    public static final Duration IDLE_TTL = Duration.ofDays(90);
    public static final int MAX_PENDING = 20;
    public static final int NAME_MAX = 60;
    public static final int LABEL_MAX = 100;
    public static final int USER_AGENT_MAX = 300;
    /** Настоящий ключ — 43 символа; длиннее — мусор, не хешируем. */
    private static final int TOKEN_MAX = 100;

    /** Состояние устройства для страницы калитки: статусы БД плюс NONE — ключа нет или он незнаком. */
    public enum GateState { NONE, PENDING, TRUSTED, REJECTED, REVOKED, EXPIRED }

    /** {@code newToken != null} — контроллер выдаёт cookie с этим ключом. */
    public record GateResult(GateState state, String code, OffsetDateTime expiresAt, String newToken) {
        static GateResult of(GateState state) {
            return new GateResult(state, null, null, null);
        }
    }

    private final TrustedDeviceRepository repo;
    private final TrustedDeviceWriter writer;
    private final DeviceRegistry registry;

    public DeviceGateService(TrustedDeviceRepository repo, TrustedDeviceWriter writer, DeviceRegistry registry) {
        this.repo = repo;
        this.writer = writer;
        this.registry = registry;
    }

    // ---------- калитка ----------

    /** Проверка на каждый запрос к ais.westmed.kz (nginx auth_request) — только реестр в памяти, без БД. */
    public boolean isTrusted(String token, OffsetDateTime now) {
        if (!plausible(token)) return false;
        String hash = DeviceTokens.hash(token);
        if (!registry.isTrusted(hash)) return false;
        registry.touch(hash, now);
        return true;
    }

    public GateResult request(String token, String rawName, String userAgent, String ip, OffsetDateTime now) {
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw new BadRequestException("Укажите имя — до " + NAME_MAX + " символов");
        }
        Optional<TrustedDevice> current = find(token);
        if (current.isPresent()) {
            TrustedDevice d = current.get();
            if (d.getStatus() == DeviceStatus.TRUSTED) return GateResult.of(GateState.TRUSTED);
            if (isFreshPending(d, now)) return pending(d, null);      // перезагрузка страницы — тот же код
        }
        if (repo.countByStatusAndRequestedAtAfter(DeviceStatus.PENDING, now.minus(PENDING_TTL)) >= MAX_PENDING) {
            throw new TooManyRequestsException("Слишком много запросов доступа — попробуйте через несколько минут");
        }
        String newToken = DeviceTokens.newToken();
        TrustedDevice d = writer.createPending(DeviceTokens.hash(newToken), freeCode(), name,
                cut(userAgent, USER_AGENT_MAX), ip, now);
        return pending(d, newToken);
    }

    public GateResult status(String token, OffsetDateTime now) {
        return find(token).map(d -> switch (d.getStatus()) {
            case PENDING -> isFreshPending(d, now) ? pending(d, null) : GateResult.of(GateState.EXPIRED);
            case TRUSTED -> GateResult.of(GateState.TRUSTED);
            case REJECTED -> GateResult.of(GateState.REJECTED);
            case REVOKED -> GateResult.of(GateState.REVOKED);
            case EXPIRED -> GateResult.of(GateState.EXPIRED);
        }).orElse(GateResult.of(GateState.NONE));
    }

    // ---------- раздел «Устройства» ----------

    public DeviceListResponse list(String currentToken, OffsetDateTime now) {
        String currentHash = plausible(currentToken) ? DeviceTokens.hash(currentToken) : null;
        DeviceListResponse r = new DeviceListResponse();
        r.setPending(map(repo.findByStatusAndRequestedAtAfterOrderByRequestedAtDesc(
                DeviceStatus.PENDING, now.minus(PENDING_TTL)), currentHash));
        r.setTrusted(map(repo.findByStatusOrderByDecidedAtDesc(DeviceStatus.TRUSTED), currentHash));
        r.setHistory(map(repo.findTop50ByStatusInOrderByRequestedAtDesc(
                List.of(DeviceStatus.REJECTED, DeviceStatus.REVOKED, DeviceStatus.EXPIRED)), currentHash));
        return r;
    }

    public long pendingCount(OffsetDateTime now) {
        return repo.countByStatusAndRequestedAtAfter(DeviceStatus.PENDING, now.minus(PENDING_TTL));
    }

    public DeviceResponse approve(Long id, String rawLabel, String admin, OffsetDateTime now) {
        String label = rawLabel == null || rawLabel.isBlank() ? null : rawLabel.strip();
        if (label != null && label.length() > LABEL_MAX) {
            throw new BadRequestException("Подпись — до " + LABEL_MAX + " символов");
        }
        TrustedDevice d = writer.approve(id, label, admin, now, now.minus(PENDING_TTL));
        registry.add(d.getTokenHash());                  // writer уже закоммитил
        return toResponse(d, null);
    }

    public DeviceResponse reject(Long id, String admin, OffsetDateTime now) {
        return toResponse(writer.reject(id, admin, now, now.minus(PENDING_TTL)), null);
    }

    public DeviceResponse revoke(Long id, String admin, OffsetDateTime now) {
        TrustedDevice d = writer.revoke(id, admin, now);
        registry.remove(d.getTokenHash());               // со следующего же запроса — на калитку
        return toResponse(d, null);
    }

    // ---------- фоновая задача (спека §8) ----------

    public void expireStale(OffsetDateTime now) {
        flushVisits();          // сперва визиты: иначе недавно заходившее устройство выпало бы по старой отметке в БД
        writer.expirePending(now.minus(PENDING_TTL));
        writer.expireIdle(now.minus(IDLE_TTL)).forEach(registry::remove);
    }

    public void flushVisits() {
        Map<String, OffsetDateTime> visits = registry.drainVisits();
        if (!visits.isEmpty()) writer.saveVisits(visits);
    }

    // ---------- внутреннее ----------

    private static boolean plausible(String token) {
        return token != null && !token.isBlank() && token.length() <= TOKEN_MAX;
    }

    private Optional<TrustedDevice> find(String token) {
        return plausible(token) ? repo.findByTokenHash(DeviceTokens.hash(token)) : Optional.empty();
    }

    private String freeCode() {
        for (int i = 0; i < 10; i++) {
            String c = DeviceTokens.newCode();
            if (!repo.existsByCodeAndStatus(c, DeviceStatus.PENDING)) return c;
        }
        throw new IllegalStateException("Не удалось подобрать свободный код запроса");
    }

    private static boolean isFreshPending(TrustedDevice d, OffsetDateTime now) {
        return d.getStatus() == DeviceStatus.PENDING && d.getRequestedAt().isAfter(now.minus(PENDING_TTL));
    }

    private static GateResult pending(TrustedDevice d, String newToken) {
        return new GateResult(GateState.PENDING, DeviceTokens.display(d.getCode()),
                d.getRequestedAt().plus(PENDING_TTL), newToken);
    }

    private static List<DeviceResponse> map(List<TrustedDevice> list, String currentHash) {
        return list.stream().map(d -> toResponse(d, currentHash)).toList();
    }

    private static DeviceResponse toResponse(TrustedDevice d, String currentHash) {
        DeviceResponse r = new DeviceResponse();
        r.setId(d.getId());
        r.setStatus(d.getStatus().name());
        r.setCode(DeviceTokens.display(d.getCode()));
        r.setRequesterName(d.getRequesterName());
        r.setLabel(d.getLabel());
        r.setDevice(UserAgentSummary.describe(d.getUserAgent()));
        r.setIp(d.getIp());
        r.setRequestedAt(d.getRequestedAt());
        r.setDecidedAt(d.getDecidedAt());
        r.setDecidedBy(d.getDecidedBy());
        r.setLastSeenAt(d.getLastSeenAt());
        r.setCurrent(currentHash != null && currentHash.equals(d.getTokenHash()));
        return r;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
