package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Колонка цены прайса (JSONB price_list.price_columns): номер среди цен строки и заголовок, как в файле. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceListColumn(int index, String header) {}
