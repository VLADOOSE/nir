package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Устройство калитки ais.westmed.kz: запрос доступа и, после решения админа, допуск (спека device-gate §4–§5).
 * Общая сущность, без рынка — как {@code UserAccount}: доступ к системе не зависит от переключателя РФ/KZ.
 */
@Entity
@Table(name = "trusted_device")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TrustedDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 (hex) ключа из cookie; сам ключ в БД не хранится. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DeviceStatus status;

    /** Код запроса без дефиса; показывается как «7K4-QM2». */
    @Column(nullable = false, length = 6)
    private String code;

    /** Имя, введённое на калитке. Не доверенное — решает код. */
    @Column(name = "requester_name", nullable = false, length = 60)
    private String requesterName;

    @Column(length = 100)
    private String label;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(length = 45)
    private String ip;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;
}
