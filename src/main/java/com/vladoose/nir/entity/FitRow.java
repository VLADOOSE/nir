package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Строка сверки для экрана: отметка, пункт ТЗ, наше значение (null — нет данных) и откуда оно. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FitRow(FitMark mark, String text, String ours, String source) {}
