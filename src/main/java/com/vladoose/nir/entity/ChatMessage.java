package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Сообщение чата — в обе стороны; уникально по (chat, externalId = idMessage Green-API). */
@Entity
@Table(name = "chat_message")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false)
    private Chat chat;

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private LeadDirection direction;

    /** Автор (важно в группах); у исходящих — null. */
    @Column(name = "sender_name")
    private String senderName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatMessageType type;

    /** Текст или подпись к файлу. */
    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    @Column(nullable = false)
    private boolean edited;

    /** «Удалено отправителем» — текст при этом сохраняется. */
    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
