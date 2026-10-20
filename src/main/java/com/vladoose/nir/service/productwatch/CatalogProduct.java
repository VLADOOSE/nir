package com.vladoose.nir.service.productwatch;

/** Товар каталога в снимке правил (без JPA). */
public record CatalogProduct(long id, String name, String brand, String slug, String category, String shortDescription,
                             String description) {}
