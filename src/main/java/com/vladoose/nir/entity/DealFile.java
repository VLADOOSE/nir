package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Файл этапа исполнения (V34). Байты читаются только при скачивании — вид берёт метаданные проекцией (DealFileMeta). */
@Entity
@Table(name = "deal_file")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DealFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DealFileStage stage;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "deal_supplier_id")
    private Long dealSupplierId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private byte[] content;

    @Column(name = "chat_attachment_id")
    private Long chatAttachmentId;

    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private OffsetDateTime uploadedAt;

    @PrePersist
    void onCreate() {
        if (uploadedAt == null) uploadedAt = OffsetDateTime.now();
    }
}
