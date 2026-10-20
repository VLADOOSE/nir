package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** РУ товара сайта по данным ndda.kz (V39, спека 2026-10-20-product-spec-fit §4.2). Не рыночная: реестр общий. */
@Entity
@Table(name = "product_registration")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProductRegistration {

    @Id
    @Column(name = "reg_number", length = 100)
    private String regNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private RegStatus status;

    @Column(name = "ndda_id")
    private Long nddaId;

    @Column(columnDefinition = "TEXT")
    private String name;

    @Column(length = 500)
    private String producer;

    @Column(length = 200)
    private String country;

    @Column(name = "reg_date")
    private LocalDate regDate;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    private Boolean unlimited;

    @Column(columnDefinition = "TEXT")
    private String purpose;

    @Column(name = "use_area", columnDefinition = "TEXT")
    private String useArea;

    @Column(name = "tech_chars", columnDefinition = "TEXT")
    private String techChars;

    @Column(name = "risk_class", length = 200)
    private String riskClass;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<RegistrationComponent> complect = new ArrayList<>();

    @Column(name = "checked_at", nullable = false)
    private OffsetDateTime checkedAt;

    @Column(length = 300)
    private String error;
}
