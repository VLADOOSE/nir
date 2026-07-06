package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Кеш одной строки комплектности аппарата НЦЭЛС (см. ComplectService). Общая сущность (без market). */
@Entity
@Table(name = "registry_component")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistryComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reg_number", nullable = false)
    private String regNumber;

    @Column(name = "part_number")
    private Integer partNumber;

    @Column(name = "product_name", columnDefinition = "TEXT")
    private String productName;

    @Column(columnDefinition = "TEXT")
    private String component;

    @Column(columnDefinition = "TEXT")
    private String producer;

    @Column(columnDefinition = "TEXT")
    private String country;

    @Column(name = "fetched_at")
    private OffsetDateTime fetchedAt;
}
