package com.vladoose.nir.dto.response;

/** Строка предпросмотра «Вставить списком»: оператор правит её до добавления. */
public record ParsedLineResponse(String name, int quantity, String unit) {}
