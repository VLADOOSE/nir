package com.vladoose.nir.dto.response;

import java.util.List;

/** Сводка «наш товар» тендера для списка (спека 2026-10-19-product-watch §12.2): уровень, подпись чипа, группы (фильтр). */
public record OurProductSummary(String level, String label, List<String> groups) {}
