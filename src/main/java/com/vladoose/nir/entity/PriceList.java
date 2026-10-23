package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Прайс поставщика (спека 2026-10-23 §5, §8). Рыночная сущность (§6 CLAUDE.md): @Filter + листенер; @FilterDef — только на
 * Tender. Строки — дети с cascade=ALL / orphanRemoval: менять только через items. @DynamicUpdate: правка шапки по полю не
 * переписывает байты файла.
 */
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@DynamicUpdate
@Entity
@Table(name = "price_list")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PriceList implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Distributor distributor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private PriceListStatus status = PriceListStatus.DRAFT;

    @Column(name = "list_date")
    private LocalDate listDate;

    @Column(name = "valid_to")
    private LocalDate validTo;

    /** Код валюты цен (KZT, RUB, USD, EUR, CNY; MIXED — не ясна; null — не названа). */
    @Column(length = 5)
    private String currency;

    /** Розничный прайс («рекомендованные розничные цены»): закупка — со скидкой. */
    @Column(nullable = false)
    private boolean retail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "price_columns", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<PriceListColumn> columns = new ArrayList<>();

    /** Номер колонки «наша закупка» среди цен строки; null — колонок цен нет. */
    @Column(name = "our_column")
    private Integer ourColumn;

    @Column(name = "discount_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal discountPct = BigDecimal.ZERO;

    @Column(length = 1000)
    private String terms;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

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

    @Column(name = "inbound_email_id")
    private Long inboundEmailId;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "activated_by", length = 100)
    private String activatedBy;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "priceList", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC, id ASC")
    @Builder.Default
    private List<PriceListItem> items = new ArrayList<>();

    @PrePersist
    void stamp() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
