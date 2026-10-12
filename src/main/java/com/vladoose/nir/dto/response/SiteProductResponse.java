package com.vladoose.nir.dto.response;

/** Товар westmed.kz в выпадающем списке «Найти на westmed.kz или вписать своё». url — пусто, если у товара нет slug. */
public record SiteProductResponse(String name, String brand, String sku, String url) {}
