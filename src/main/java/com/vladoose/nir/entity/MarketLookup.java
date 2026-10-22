package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Что и когда запрашивали у площадки для цен рынка (спека §9): «w:<слово>», «c:<БИН>», «daily». */
@Entity
@Table(name = "market_lookup")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketLookup {

    @Id
    @Column(name = "lookup_key", length = 150)
    private String lookupKey;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    @Column(name = "rows_count", nullable = false)
    private int rowsCount;

    @Column(length = 300)
    private String error;
}
