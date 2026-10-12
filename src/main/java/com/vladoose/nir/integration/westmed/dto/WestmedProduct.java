package com.vladoose.nir.integration.westmed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Элемент публичного каталога сайта: бренд и slug для ссылки, артикул — для выпадающего списка частной заявки. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WestmedProduct(String name, String slug, String brandName, String sku) {

    public WestmedProduct(String name, String slug, String brandName) {
        this(name, slug, brandName, null);
    }
}
