package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Файл готового КП (V33, спека 2026-10-19 §5.2): байты документа, итог разбора, таблицы файла для просмотра, «ИТОГО».
 * Один на КП (offer_id UNIQUE), уходит вместе с КП (ON DELETE CASCADE). Не рыночный — только через КП с гардом рынка.
 * Байты грузятся вместе с сущностью: журнал и ответ КП берут сведения проекцией (ClientOfferFileMeta).
 */
@Entity
@Table(name = "client_offer_file")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClientOfferFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "offer_id", nullable = false, unique = true)
    private Long offerId;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReplyFileKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 12)
    private ReplyParseStatus parseStatus;

    @Column(name = "parse_note", columnDefinition = "TEXT")
    private String parseNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<ReplyTable> tables;

    private Integer pages;

    @Column(name = "file_total", precision = 15, scale = 2)
    private BigDecimal fileTotal;

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
