package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Сырое событие шлюза WhatsApp в очереди (спека whatsapp-waha §4–5). Вставляется только WahaInboxWriter'ом
 * (INSERT … ON CONFLICT), читается циклом приёма по (event_at, id). Не рыночная: рынок ставится при записи чата.
 */
@Entity
@Table(name = "whatsapp_inbox")
@Getter @Setter @NoArgsConstructor
public class WhatsappInboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String provider;

    /** X-Webhook-Request-Id — одинаков во всех повторах WAHA; у догонки null. */
    @Column(name = "request_id", length = 100)
    private String requestId;

    /** «fromMe_rawId» у message.any (вебхук и догонка); у прочих событий null. */
    @Column(name = "message_key", length = 200)
    private String messageKey;

    @Column(nullable = false, length = 40)
    private String event;

    @Column(name = "event_at", nullable = false)
    private OffsetDateTime eventAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private WhatsappInboxStatus status;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "received_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;
}
