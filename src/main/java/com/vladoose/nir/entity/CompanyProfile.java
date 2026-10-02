package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Реквизиты компании рынка и умолчания КП (спека client-kp-constructor §4.1). Строка на рынок, как EmailTemplate:
 * НЕ рыночная сущность (без @Filter), читается по MarketContext.get(). Картинки — уже обработанный PNG.
 */
@Entity
@Table(name = "company_profile")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompanyProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 2)
    private Market market;

    @Column(name = "short_name", nullable = false)
    private String shortName;

    @Column(name = "full_name", length = 500)
    private String fullName;

    @Column(name = "header_left", columnDefinition = "TEXT")
    private String headerLeft;

    @Column(name = "header_right", columnDefinition = "TEXT")
    private String headerRight;

    @Column(name = "brand_text", length = 100)
    private String brandText;

    @Column(name = "ids_line", length = 500)
    private String idsLine;

    @Column(name = "bin_inn", length = 20)
    private String binInn;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(columnDefinition = "TEXT")
    private String accounts;

    @Column(name = "bank_name")
    private String bankName;

    @Column(length = 20)
    private String bik;

    @Column(length = 100)
    private String phone;

    private String email;

    @Column(name = "director_title", length = 100)
    private String directorTitle;

    @Column(name = "director_name")
    private String directorName;

    @Column(name = "signoff_contacts", length = 500)
    private String signoffContacts;

    @Column(name = "logo_png")
    private byte[] logoPng;

    @Column(name = "stamp_png")
    private byte[] stampPng;

    @Column(name = "signature_png")
    private byte[] signaturePng;

    @Column(name = "images_updated_at")
    private OffsetDateTime imagesUpdatedAt;

    @Column(name = "stamp_size_mm", nullable = false)
    @Builder.Default
    private int stampSizeMm = 40;

    /** Ставки рынка; null-элемент = «Без НДС». */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vat_rates", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<BigDecimal> vatRates = new ArrayList<>();

    /** Ставка новой строки; null = без НДС. */
    @Column(name = "vat_default", precision = 5, scale = 2)
    private BigDecimal vatDefault;

    @Column(name = "vat_registered", precision = 5, scale = 2)
    private BigDecimal vatRegistered;

    @Column(name = "vat_not_registrable", precision = 5, scale = 2)
    private BigDecimal vatNotRegistrable;

    @Column(name = "default_markup_pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal defaultMarkupPct;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_columns", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<OfferColumn> defaultColumns = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_terms", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<OfferTerm> defaultTerms = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "default_terms_style", nullable = false, length = 10)
    @Builder.Default
    private TermsStyle defaultTermsStyle = TermsStyle.LIST;

    @Column(name = "default_intro", columnDefinition = "TEXT")
    private String defaultIntro;

    /**
     * Следующий «исх. №». Пишут только CompanyProfileService.allocateNumber и явная правка поля (оба — JDBC); через
     * JPA колонка не обновляется: сохранение профиля со старым номером в памяти не откатит выданные номера.
     */
    @Column(name = "next_number", nullable = false, updatable = false)
    @Builder.Default
    private int nextNumber = 1;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
