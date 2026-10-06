package com.vladoose.nir.integration;

/** Лот для фильтров релевантности импорта: название и описание раздельно (маркеры услуг ищутся только в названии). */
public record LotText(String name, String description) {}
