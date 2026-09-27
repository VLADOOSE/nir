package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Событие ленты обращения. direction/channel/externalId — задел под звонки АТС и сообщения WhatsApp. */
@Entity
@Table(name = "lead_event")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeadEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    private Lead lead;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeadEventType type;

    @Enumerated(EnumType.STRING)
    @Column(length = 3)
    private LeadDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private LeadChannel channel;

    @Column(length = 100)
    private String author;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "external_id", length = 100)
    private String externalId;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) occurredAt = OffsetDateTime.now();
    }
}
