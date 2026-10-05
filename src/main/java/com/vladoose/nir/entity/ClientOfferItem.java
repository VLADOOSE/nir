package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** Строка КП (спека §4.3). Числа расчёта не хранятся — только ввод оператора. Связи волны 2 — голые id. */
@Entity
@Table(name = "client_offer_item")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClientOfferItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_id", nullable = false)
    private ClientOffer offer;

    /** Порядковый номер строки (не «position»: это функция HQL, разбор @OrderBy по ней ненадёжен). */
    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ClientOfferItemKind kind = ClientOfferItemKind.ITEM;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String name = "";

    @Column(length = 255)
    private String model;

    @Column(length = 500)
    private String manufacturer;

    @Column(length = 200)
    private String country;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String unit = "шт";

    @Column(precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "purchase_price", precision = 15, scale = 2)
    private BigDecimal purchasePrice;

    /**
     * Пометка «НДС в цене закупки» (+ purchaseVatRate) — с 2026-10-05 расчёт её не читает: НДС поставщика не учитывается
     * (решение оператора, спека §5.1). Колонки оставлены для совместимости — старые КП и API их по-прежнему несут.
     */
    @Column(name = "purchase_vat_same", nullable = false)
    @Builder.Default
    private boolean purchaseVatSame = true;

    @Column(name = "purchase_vat_rate", precision = 5, scale = 2)
    private BigDecimal purchaseVatRate;

    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "distributor_id")
    private Long distributorId;

    @Column(name = "tender_lot_id")
    private Long tenderLotId;

    @Column(name = "price_request_item_id")
    private Long priceRequestItemId;

    @Column(name = "med_equipment_id")
    private Long medEquipmentId;

    @Column(name = "markup_pct", precision = 7, scale = 2)
    private BigDecimal markupPct;

    @Column(name = "price_override", precision = 15, scale = 2)
    private BigDecimal priceOverride;

    @Column(name = "vat_rate", precision = 5, scale = 2)
    private BigDecimal vatRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_status", nullable = false, length = 20)
    @Builder.Default
    private OfferRegistrationStatus registrationStatus = OfferRegistrationStatus.UNCHECKED;

    @Column(name = "registration_text", columnDefinition = "TEXT")
    private String registrationText;

    @Column(name = "reg_number", length = 100)
    private String regNumber;

    @Column(name = "suggestion_score", precision = 5, scale = 3)
    private BigDecimal suggestionScore;

    @Column(columnDefinition = "TEXT")
    private String note;

    /** Ключ строки на клиенте (автосохранение сводит ответ со своими строками по нему). Не хранится. */
    @Transient
    private String clientKey;
}
