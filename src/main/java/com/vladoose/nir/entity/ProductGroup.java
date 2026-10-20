package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Группа «нашей продукции» (V36, спека 2026-10-19-product-watch §4.1). Не рыночная сущность с явным рынком (как
 * reply_template): читается и пишется по MarketContext, без @Filter.
 */
@Entity
@Table(name = "product_group")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProductGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Column(nullable = false, length = 120)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "search_words", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> searchWords = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "require_words", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> requireWords = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exclude_words", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> excludeWords = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "brand_words", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> brandWords = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "brand_group", nullable = false)
    private boolean brandGroup;

    @Column(nullable = false)
    private int position;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
