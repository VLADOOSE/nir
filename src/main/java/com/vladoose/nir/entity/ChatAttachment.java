package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Файл сообщения. Байты читаются только при скачивании — лента берёт метаданные проекцией
 * (ChatAttachmentRepository.findMetaByMessageIds). Инвариант: content == null ⇔ notStoredReason != null.
 */
@Entity
@Table(name = "chat_attachment")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false, unique = true)
    private ChatMessage message;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "content")
    private byte[] content;

    @Enumerated(EnumType.STRING)
    @Column(name = "not_stored_reason", length = 20)
    private AttachmentNotStoredReason notStoredReason;
}
