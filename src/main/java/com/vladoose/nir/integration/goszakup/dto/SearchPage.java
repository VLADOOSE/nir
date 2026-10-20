package com.vladoose.nir.integration.goszakup.dto;

import java.util.List;

/** Страница v3: элементы и курсор следующей страницы (null — последняя). */
public record SearchPage<T>(List<T> items, Long nextAfter) {}
