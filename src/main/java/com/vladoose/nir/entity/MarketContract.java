package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Строка набора цен рынка (спека §9): состоявшийся лот goszakup с договором. Ключ — id лота площадки. */
@Entity
@Table(name = "market_contract")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lot_ext_id", nullable = false, unique = true)
    private Long lotExtId;

    @Column(length = 30)
    private String anno;

    @Column(name = "lot_number", length = 50)
    private String lotNumber;

    @Column(name = "enstru_code", length = 30)
    private String enstruCode;

    @Column(length = 500)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "extra_desc", columnDefinition = "TEXT")
    private String extraDesc;

    @Column(length = 50)
    private String unit;

    @Column(name = "customer_bin", length = 20)
    private String customerBin;

    @Column(name = "customer_name", length = 500)
    private String customerName;

    @Column(length = 100)
    private String region;

    @Column(precision = 15, scale = 3)
    private BigDecimal quantity;

    @Column(name = "budget_unit_price", precision = 15, scale = 2)
    private BigDecimal budgetUnitPrice;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "supplier_bin", length = 20)
    private String supplierBin;

    @Column(name = "signed_at")
    private LocalDate signedAt;

    @Column(name = "contract_number", length = 60)
    private String contractNumber;

    @Column(name = "trade_method", length = 150)
    private String tradeMethod;

    @Column(name = "fetched_at", nullable = false)
    @Builder.Default
    private OffsetDateTime fetchedAt = OffsetDateTime.now();
}
