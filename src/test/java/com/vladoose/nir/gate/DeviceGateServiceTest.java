package com.vladoose.nir.gate;

import com.vladoose.nir.dto.response.DeviceListResponse;
import com.vladoose.nir.dto.response.DeviceResponse;
import com.vladoose.nir.entity.DeviceStatus;
import com.vladoose.nir.entity.TrustedDevice;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.exception.TooManyRequestsException;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import com.vladoose.nir.service.DeviceGateService;
import com.vladoose.nir.service.DeviceGateService.GateResult;
import com.vladoose.nir.service.DeviceGateService.GateState;
import com.vladoose.nir.service.DeviceRegistry;
import com.vladoose.nir.util.DeviceTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class DeviceGateServiceTest {

    @Autowired DeviceGateService gate;
    @Autowired TrustedDeviceRepository repo;

    private static final OffsetDateTime NOW = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";

    private TrustedDevice row(String token) {
        return repo.findByTokenHash(DeviceTokens.hash(token)).orElseThrow();
    }

    /** Ключ допущенного устройства: запрос в момент {@code at}, допуск минутой позже. */
    private String trustedAt(OffsetDateTime at) {
        GateResult r = gate.request(null, "Асель", IPHONE, "5.6.7.8", at);
        gate.approve(row(r.newToken()).getId(), null, "admin1", at.plusMinutes(1));
        return r.newToken();
    }

    @Test
    void requestIssuesKeyAndReadableCode() {
        GateResult r = gate.request(null, "  Асель ", IPHONE, "5.6.7.8", NOW);

        assertThat(r.state()).isEqualTo(GateState.PENDING);
        assertThat(r.code()).matches("[2-9A-HJKMNP-Z]{3}-[2-9A-HJKMNP-Z]{3}");
        assertThat(r.newToken()).hasSize(43);
        assertThat(r.expiresAt()).isAtSameInstantAs(NOW.plusMinutes(15));
        TrustedDevice d = row(r.newToken());
        assertThat(d.getStatus()).isEqualTo(DeviceStatus.PENDING);
        assertThat(d.getRequesterName()).isEqualTo("Асель");
        assertThat(d.getIp()).isEqualTo("5.6.7.8");
        assertThat(d.getTokenHash()).isNotEqualTo(r.newToken());     // в БД — только хеш
    }

    @Test
    void repeatedRequestFromWaitingDeviceKeepsItsCode() {
        GateResult first = gate.request(null, "Асель", null, null, NOW);
        long rows = repo.count();

        GateResult again = gate.request(first.newToken(), "Асель", null, null, NOW.plusMinutes(3));

        assertThat(again.state()).isEqualTo(GateState.PENDING);
        assertThat(again.code()).isEqualTo(first.code());
        assertThat(again.newToken()).isNull();
        assertThat(repo.count()).isEqualTo(rows);
    }

    @Test
    void requestFromTrustedDeviceIssuesNothing() {
        String token = trustedAt(NOW);

        GateResult r = gate.request(token, "Асель", null, null, NOW.plusMinutes(2));

        assertThat(r.state()).isEqualTo(GateState.TRUSTED);
        assertThat(r.newToken()).isNull();
    }

    @Test
    void requestAfterExpiryIssuesNewKey() {
        GateResult old = gate.request(null, "Асель", null, null, NOW.minusMinutes(16));

        GateResult fresh = gate.request(old.newToken(), "Асель", null, null, NOW);

        assertThat(fresh.newToken()).isNotNull().isNotEqualTo(old.newToken());
        assertThat(fresh.code()).isNotEqualTo(old.code());           // код старого ещё занят среди PENDING
    }

    @Test
    void nameIsRequiredAndBounded() {
        assertThatThrownBy(() -> gate.request(null, "   ", null, null, NOW)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> gate.request(null, null, null, null, NOW)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> gate.request(null, "я".repeat(61), null, null, NOW)).isInstanceOf(BadRequestException.class);
        assertThat(gate.request(null, "я".repeat(60), null, null, NOW).state()).isEqualTo(GateState.PENDING);
    }

    @Test
    void waitingRequestsAreCappedAtTwenty() {
        for (long i = gate.pendingCount(NOW); i < DeviceGateService.MAX_PENDING; i++) {
            gate.request(null, "Бот " + i, null, null, NOW);
        }

        assertThatThrownBy(() -> gate.request(null, "Лишний", null, null, NOW)).isInstanceOf(TooManyRequestsException.class);
        // истёкшие запросы в лимит не входят
        assertThat(gate.request(null, "Позже", null, null, NOW.plusMinutes(16)).state()).isEqualTo(GateState.PENDING);
    }

    @Test
    void checkLetsInOnlyTrustedDevices() {
        GateResult waiting = gate.request(null, "Асель", null, null, NOW);
        assertThat(gate.isTrusted(waiting.newToken(), NOW)).isFalse();

        gate.approve(row(waiting.newToken()).getId(), null, "admin1", NOW.plusMinutes(1));
        assertThat(gate.isTrusted(waiting.newToken(), NOW.plusMinutes(2))).isTrue();   // сразу, без перезапуска

        assertThat(gate.isTrusted(null, NOW)).isFalse();
        assertThat(gate.isTrusted("", NOW)).isFalse();
        assertThat(gate.isTrusted("не-ключ", NOW)).isFalse();
        assertThat(gate.isTrusted("x".repeat(500), NOW)).isFalse();
    }

    @Test
    void approveDefaultsLabelToRequesterAndRecordsAdmin() {
        GateResult r = gate.request(null, "Асель", IPHONE, "5.6.7.8", NOW);

        DeviceResponse d = gate.approve(row(r.newToken()).getId(), "  ", "admin1", NOW.plusMinutes(1));

        assertThat(d.getStatus()).isEqualTo("TRUSTED");
        assertThat(d.getLabel()).isEqualTo("Асель");
        assertThat(d.getDecidedBy()).isEqualTo("admin1");
        assertThat(d.getDevice()).isEqualTo("iPhone · Safari");

        GateResult r2 = gate.request(null, "Марат", null, null, NOW);
        assertThat(gate.approve(row(r2.newToken()).getId(), " Ноутбук Марата ", "admin1", NOW).getLabel())
                .isEqualTo("Ноутбук Марата");
        GateResult r3 = gate.request(null, "Длинный", null, null, NOW);
        assertThatThrownBy(() -> gate.approve(row(r3.newToken()).getId(), "я".repeat(101), "admin1", NOW))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectedAndRevokedDevicesStayOutside() {
        GateResult rejected = gate.request(null, "Чужой", null, null, NOW);
        gate.reject(row(rejected.newToken()).getId(), "admin1", NOW.plusMinutes(1));
        assertThat(gate.isTrusted(rejected.newToken(), NOW)).isFalse();
        assertThat(gate.status(rejected.newToken(), NOW).state()).isEqualTo(GateState.REJECTED);

        String token = trustedAt(NOW);
        gate.revoke(row(token).getId(), "admin1", NOW.plusMinutes(5));
        assertThat(gate.isTrusted(token, NOW.plusMinutes(6))).isFalse();              // сразу
        assertThat(gate.status(token, NOW).state()).isEqualTo(GateState.REVOKED);
    }

    @Test
    void decisionsOnlyFromTheRightState() {
        String token = trustedAt(NOW);
        Long trustedId = row(token).getId();
        assertThatThrownBy(() -> gate.approve(trustedId, null, "a", NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> gate.reject(trustedId, "a", NOW)).isInstanceOf(ConflictException.class);

        GateResult waiting = gate.request(null, "Асель", null, null, NOW);
        assertThatThrownBy(() -> gate.revoke(row(waiting.newToken()).getId(), "a", NOW)).isInstanceOf(ConflictException.class);

        GateResult stale = gate.request(null, "Асель", null, null, NOW.minusMinutes(16));
        assertThatThrownBy(() -> gate.approve(row(stale.newToken()).getId(), null, "a", NOW)).isInstanceOf(ConflictException.class);

        assertThatThrownBy(() -> gate.approve(-1L, null, "a", NOW)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void statusFollowsTheLifecycle() {
        assertThat(gate.status(null, NOW).state()).isEqualTo(GateState.NONE);
        assertThat(gate.status("незнакомый", NOW).state()).isEqualTo(GateState.NONE);

        GateResult r = gate.request(null, "Асель", null, null, NOW);
        GateResult st = gate.status(r.newToken(), NOW.plusMinutes(1));
        assertThat(st.state()).isEqualTo(GateState.PENDING);
        assertThat(st.code()).isEqualTo(r.code());
        assertThat(gate.status(r.newToken(), NOW.plusMinutes(16)).state()).isEqualTo(GateState.EXPIRED);
    }

    @Test
    void listShowsPendingTrustedHistoryAndCurrentDevice() {
        gate.request(null, "Ждёт", null, null, NOW);
        String mine = trustedAt(NOW);
        GateResult refused = gate.request(null, "Отказ", null, null, NOW);
        gate.reject(row(refused.newToken()).getId(), "admin1", NOW);

        DeviceListResponse l = gate.list(mine, NOW.plusMinutes(2));

        assertThat(l.getPending()).extracting(DeviceResponse::getRequesterName).contains("Ждёт");
        assertThat(l.getTrusted()).filteredOn(DeviceResponse::isCurrent).singleElement()
                .satisfies(d -> assertThat(d.getLabel()).isEqualTo("Асель"));
        assertThat(l.getHistory()).extracting(DeviceResponse::getRequesterName).contains("Отказ");
        assertThat(l.getPending()).noneMatch(DeviceResponse::isCurrent);
    }

    @Test
    void visitsReachTheDatabaseInBatches() {
        String token = trustedAt(NOW);
        gate.isTrusted(token, NOW.plusMinutes(10));
        assertThat(row(token).getLastSeenAt()).isNull();                                 // пока только в памяти

        gate.flushVisits();

        assertThat(row(token).getLastSeenAt()).isAtSameInstantAs(NOW.plusMinutes(10));
    }

    @Test
    void expiryKeepsRecentlyVisitedDevices() {
        GateResult stale = gate.request(null, "Старый", null, null, NOW.minusMinutes(20));
        String idle = trustedAt(NOW.minusDays(91));
        String active = trustedAt(NOW.minusDays(91));
        gate.isTrusted(active, NOW.minusHours(1));                                      // визит — пока только в памяти

        gate.expireStale(NOW);

        assertThat(row(stale.newToken()).getStatus()).isEqualTo(DeviceStatus.EXPIRED);
        assertThat(row(idle).getStatus()).isEqualTo(DeviceStatus.EXPIRED);
        assertThat(gate.isTrusted(idle, NOW)).isFalse();
        TrustedDevice kept = row(active);
        assertThat(kept.getStatus()).isEqualTo(DeviceStatus.TRUSTED);
        assertThat(kept.getLastSeenAt()).isAtSameInstantAs(NOW.minusHours(1));          // визит записан до проверки простоя
        assertThat(gate.isTrusted(active, NOW)).isTrue();
    }

    @Test
    void registryRestoresTrustedDevicesAfterRestart() {
        String token = trustedAt(NOW);

        DeviceRegistry fresh = new DeviceRegistry(repo);                                // как после перезапуска
        fresh.load();

        assertThat(fresh.isTrusted(DeviceTokens.hash(token))).isTrue();
    }
}
