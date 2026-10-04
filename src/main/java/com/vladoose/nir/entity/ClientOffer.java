package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * КП клиенту (спека client-kp-constructor §4.2). Рыночная сущность (§6 CLAUDE.md): @Filter + листенер штампа;
 * @FilterDef объявлен ОДИН раз — на Tender. Строки — дети с cascade=ALL/orphanRemoval: менять ТОЛЬКО через items.
 * Числа расчёта не хранятся (считает ClientOfferCalculator); totalAmount/itemCount — копия для журнала.
 */
@Entity
@Table(name = "client_offer")
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClientOffer implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Column(nullable = false)
    private Integer number;

    @Column(name = "offer_date", nullable = false)
    private LocalDate offerDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ClientOfferStatus status = ClientOfferStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "facility_id")
    private Facility facility;

    @Column(columnDefinition = "TEXT")
    private String recipient;

    /** Частная заявка-источник (волна 2) — id без связи, чтобы не тянуть тендер с лотами в каждый КП. */
    @Column(name = "tender_id")
    private Long tenderId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String intro;

    @Column(name = "vat_enabled", nullable = false)
    @Builder.Default
    private boolean vatEnabled = true;

    @Column(name = "default_markup_pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal defaultMarkupPct;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private OfferRounding rounding = OfferRounding.NONE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "table_columns", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<OfferColumn> tableColumns = new ArrayList<>();

    @Column(name = "details_in_name", nullable = false)
    @Builder.Default
    private boolean detailsInName = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<OfferTerm> terms = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "terms_style", nullable = false, length = 10)
    @Builder.Default
    private TermsStyle termsStyle = TermsStyle.LIST;

    @Column(name = "show_amount_in_words", nullable = false)
    @Builder.Default
    private boolean showAmountInWords = true;

    @Column(name = "show_vat_breakdown", nullable = false)
    @Builder.Default
    private boolean showVatBreakdown = true;

    @Column(nullable = false)
    private boolean landscape;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private OfferSignoff signoff = OfferSignoff.DIRECTOR;

    @Column(name = "signoff_contacts", nullable = false)
    private boolean signoffContacts;

    @Column(name = "with_stamp", nullable = false)
    private boolean withStamp;

    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "internal_note", columnDefinition = "TEXT")
    private String internalNote;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @OneToMany(mappedBy = "offer", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    @Builder.Default
    private List<ClientOfferItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
