package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Наш курс (спека 2026-10-23 §4.1): 1 единица валюты = ourRate валюты рынка. Не рыночная с явным рынком (как
 * reply_template): читается и пишется по MarketContext, без @Filter.
 */
@Entity
@Table(name = "currency_rate")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CurrencyRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "our_rate", nullable = false, precision = 14, scale = 6)
    private BigDecimal ourRate;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
