package com.vladoose.nir.gate;

import com.vladoose.nir.entity.DeviceStatus;
import com.vladoose.nir.entity.TrustedDevice;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class TrustedDevicePersistenceTest {

    @Autowired TrustedDeviceRepository repo;

    private static final OffsetDateTime NOW = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);

    private static TrustedDevice device(String code, DeviceStatus status, OffsetDateTime requestedAt) {
        String hash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");   // 64 hex
        return TrustedDevice.builder().tokenHash(hash).status(status).code(code)
                .requesterName("Проверка").requestedAt(requestedAt).build();
    }

    @Test
    void savesAndFindsByTokenHash() {
        TrustedDevice d = repo.saveAndFlush(device("ABCDEF", DeviceStatus.PENDING, NOW));

        TrustedDevice found = repo.findByTokenHash(d.getTokenHash()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(DeviceStatus.PENDING);
        assertThat(found.getRequesterName()).isEqualTo("Проверка");
        assertThat(found.getRequestedAt()).isAtSameInstantAs(NOW);
    }

    @Test
    void codeIsUniqueOnlyAmongPending() {
        repo.saveAndFlush(device("QWERTY", DeviceStatus.EXPIRED, NOW));
        repo.saveAndFlush(device("QWERTY", DeviceStatus.PENDING, NOW));     // вне PENDING код может повторяться

        assertThatThrownBy(() -> repo.saveAndFlush(device("QWERTY", DeviceStatus.PENDING, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void movesOnlyStaleRequests() {
        TrustedDevice stale = repo.saveAndFlush(device("STALE2", DeviceStatus.PENDING, NOW.minusMinutes(20)));
        TrustedDevice fresh = repo.saveAndFlush(device("FRESH2", DeviceStatus.PENDING, NOW.minusMinutes(5)));

        repo.moveRequestedBefore(DeviceStatus.PENDING, DeviceStatus.EXPIRED, NOW.minusMinutes(15));

        assertThat(repo.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(DeviceStatus.EXPIRED);
        assertThat(repo.findById(fresh.getId()).orElseThrow().getStatus()).isEqualTo(DeviceStatus.PENDING);
    }

    @Test
    void markSeenTouchesOnlyTrusted() {
        TrustedDevice trusted = repo.saveAndFlush(device("TRUST2", DeviceStatus.TRUSTED, NOW));
        TrustedDevice revoked = repo.saveAndFlush(device("REVOK2", DeviceStatus.REVOKED, NOW));

        assertThat(repo.markSeen(trusted.getTokenHash(), NOW, DeviceStatus.TRUSTED)).isEqualTo(1);
        assertThat(repo.markSeen(revoked.getTokenHash(), NOW, DeviceStatus.TRUSTED)).isZero();
        assertThat(repo.findById(trusted.getId()).orElseThrow().getLastSeenAt()).isAtSameInstantAs(NOW);
    }
}
