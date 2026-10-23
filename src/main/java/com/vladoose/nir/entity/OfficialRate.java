package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Официальный курс (спека §4.2): источник NBRK, курс за 1 единицу валюты в тенге на дату; история копится сама. */
@Entity
@Table(name = "official_rate")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OfficialRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String source;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal rate;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;
}
