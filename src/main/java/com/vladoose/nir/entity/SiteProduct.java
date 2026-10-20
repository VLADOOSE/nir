package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Товар каталога westmed.kz (V36, спека §4.2): правится только на сайте, АИС раз в сутки забирает копию. */
@Entity
@Table(name = "site_product")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SiteProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Column(name = "site_id", nullable = false, unique = true)
    private UUID siteId;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(length = 50)
    private String sku;

    @Column(length = 500)
    private String slug;

    @Column(length = 200)
    private String brand;

    @Column(length = 200)
    private String category;

    @Column(name = "short_description", columnDefinition = "TEXT")
    private String shortDescription;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<SiteProductAttribute> attributes = new ArrayList<>();

    @Column(name = "registration_cert", length = 200)
    private String registrationCert;

    @Column(name = "site_updated_at")
    private OffsetDateTime siteUpdatedAt;

    @Column(name = "synced_at", nullable = false)
    private OffsetDateTime syncedAt;

    @Column(name = "removed_at")
    private OffsetDateTime removedAt;
}
