package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Событие ленты сделки (V32). Не рыночная: читается только через заявку / тендер с гардом рынка. */
@Entity
@Table(name = "deal_event")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DealEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DealEventType type;

    /** Для STAGE: OFFER_STATUS, CLOSED, REOPENED, APPLY_STATUS, WINNER. */
    @Column(length = 40)
    private String code;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String text;

    @Column(length = 100)
    private String author;

    /** КП / заявка на участие / лот — по коду события. */
    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) occurredAt = OffsetDateTime.now();
    }
}
