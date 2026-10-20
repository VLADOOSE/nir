package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Характеристика товара сайта (JSONB `site_product.attributes`). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SiteProductAttribute(String name, String value) {}
