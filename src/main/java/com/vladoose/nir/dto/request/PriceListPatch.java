package com.vladoose.nir.dto.request;

/** Правка прайса или строки по полю (спека §5.3, как у частной заявки): field — имя поля, value — текст (пусто — снять). */
public record PriceListPatch(String field, String value) {}
