package com.vladoose.nir.dto.response;

import java.util.List;

/**
 * Сводка «наш товар» тендера для списка (спека 2026-10-19-product-watch §12.2): уровень, подпись чипа, группы (фильтр);
 * fit — лучшая сверка ТЗ с нашим товаром (вердикт, ✓, ✗, всего) для сортировки «Ближе к нашему товару»; null — сверка
 * ничего не установила («◐ похоже» без ✓) или её не было.
 */
public record OurProductSummary(String level, String label, List<String> groups, Fit fit) {

    public record Fit(String verdict, int met, int failed, int total) {}
}
