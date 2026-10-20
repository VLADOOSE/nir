package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Отметка «наш товар» на лоте (V36, спека §4.3): строка на пару «лот × группа». Не рыночная — через тендер
 * (читать только после гарда рынка тендера), каскадом от тендера, лота и группы.
 */
@Entity
@Table(name = "tender_lot_product_match")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TenderLotProductMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "lot_id", nullable = false)
    private Long lotId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(length = 100)
    private String word;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ProductMatchLevel level;

    @Column(length = 1000)
    private String evidence;

    @Column(name = "product_id")
    private Long productId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "similar_products", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<SimilarProductRef> similar = new ArrayList<>();

    @Column(name = "spec_checked_at")
    private OffsetDateTime specCheckedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ProductMatchSource source;

    @Column(name = "matched_at", nullable = false)
    @Builder.Default
    private OffsetDateTime matchedAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
