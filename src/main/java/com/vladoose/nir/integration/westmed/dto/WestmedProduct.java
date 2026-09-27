package com.vladoose.nir.integration.westmed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Элемент публичного каталога сайта — нужен ради бренда и slug для ссылки. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WestmedProduct(String name, String slug, String brandName) {}
