package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Файл ответа поставщика (спека 2026-10-15 §3.1): из письма с меткой [КП-…] или загруженный вручную. НЕ рыночный —
 * доступ только через запрос цен с явным гардом рынка (SupplierReplyService.guard). parsed — разобранная таблица
 * Excel / Word (роли колонок правятся в ней же). @DynamicUpdate: курс, роли колонок и ссылку на письмо меняют разные
 * запросы — UPDATE пишет только изменённое (и не переписывает байты файла).
 */
@DynamicUpdate
@Entity
@Table(name = "supplier_reply_file")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplierReplyFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "price_request_id", nullable = false)
    private Long priceRequestId;

    /** Строка «Входящих», из письма которой файл; пусто — загружен вручную (или письмо ещё не записано). */
    @Column(name = "inbound_email_id")
    private Long inboundEmailId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(length = 64)
    private String sha256;

    /** Байты файла; пусто — больше предела хранения. */
    @Column(name = "data")
    private byte[] data;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReplyFileKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 20)
    private ReplyParseStatus parseStatus;

    @Column(name = "parse_note", length = 300)
    private String parseNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed", columnDefinition = "jsonb")
    private ReplyTable parsed;

    @Column(name = "page_count")
    private Integer pageCount;

    /** Цены в тексте файла без таблицы (Word, PDF; V42) — подсказка экрана сверки; null — нет или файл старый. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "text_prices", columnDefinition = "jsonb")
    private java.util.List<TextPrice> textPrices;

    /** Логин того, кто загрузил файл вручную; у файла из письма — пусто. */
    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    /** Курс пересчёта цен файла в валюту запроса: 1 rateCurrency = rate валюты рынка (V31); пусто — не пересчитывать. */
    @Column(name = "rate", precision = 14, scale = 6)
    private java.math.BigDecimal rate;

    @Column(name = "rate_currency", length = 8)
    private String rateCurrency;

    @Column(name = "rate_set_at")
    private OffsetDateTime rateSetAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void stamp() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
