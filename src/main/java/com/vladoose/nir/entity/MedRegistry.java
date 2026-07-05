package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "med_registry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedRegistry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reg_number", nullable = false, unique = true, length = 100)
    private String regNumber;

    @Column(nullable = false, columnDefinition = "TEXT")
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

    @Column(name = "imported_at")
    private OffsetDateTime importedAt;

    // --- Кеш карточки НЦЭЛС (on-demand, см. RegistryDetailService) ---

    @Column(name = "ndda_id")
    private Long nddaId;

    @Column(name = "risk_class", columnDefinition = "TEXT")
    private String riskClass;

    @Column(columnDefinition = "TEXT")
    private String purpose;

    @Column(name = "use_area", columnDefinition = "TEXT")
    private String useArea;

    @Column(name = "tech_chars", columnDefinition = "TEXT")
    private String techChars;

    @Column(name = "mi_kind", columnDefinition = "TEXT")
    private String miKind;

    @Column(name = "mi_kind_def", columnDefinition = "TEXT")
    private String miKindDef;

    @Column(name = "detail_fetched_at")
    private OffsetDateTime detailFetchedAt;
}
