package com.vladoose.nir.repository;

import com.vladoose.nir.entity.DeviceStatus;
import com.vladoose.nir.entity.TrustedDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Long> {

    Optional<TrustedDevice> findByTokenHash(String tokenHash);

    List<TrustedDevice> findByStatus(DeviceStatus status);

    List<TrustedDevice> findByStatusAndRequestedAtAfterOrderByRequestedAtDesc(DeviceStatus status, OffsetDateTime after);

    List<TrustedDevice> findByStatusOrderByDecidedAtDesc(DeviceStatus status);

    List<TrustedDevice> findTop50ByStatusInOrderByRequestedAtDesc(Collection<DeviceStatus> statuses);

    long countByStatusAndRequestedAtAfter(DeviceStatus status, OffsetDateTime after);

    boolean existsByCodeAndStatus(String code, DeviceStatus status);

    /** Массово перевести запросы, поданные раньше {@code cutoff}, из {@code from} в {@code to}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TrustedDevice d set d.status = :to where d.status = :from and d.requestedAt < :cutoff")
    int moveRequestedBefore(@Param("from") DeviceStatus from, @Param("to") DeviceStatus to,
                            @Param("cutoff") OffsetDateTime cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TrustedDevice d set d.lastSeenAt = :seen where d.tokenHash = :hash and d.status = :status")
    int markSeen(@Param("hash") String hash, @Param("seen") OffsetDateTime seen, @Param("status") DeviceStatus status);
}
