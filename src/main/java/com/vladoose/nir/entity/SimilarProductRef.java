package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Похожий наш товар (JSONB `tender_lot_product_match.similar`). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimilarProductRef(long productId, String name, String brand, String slug, double score) {}
