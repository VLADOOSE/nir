package com.vladoose.nir.dto.response;

import java.util.List;

/**
 * Отметка «наш товар» лота для карточки тендера (спеки 2026-10-19-product-watch §12.3, 2026-10-20-product-spec-fit §8.1):
 * ссылки — на товары сайта; сверка — лучший товар и варианты со строками.
 */
public record LotProductMatchDto(String groupName, String word, String level, String evidence, String productName, String productUrl,
                                 List<Similar> similar, String fit, String foreignBrand, List<Candidate> candidates) {
    public record Similar(String name, String brand, String url) {}
    public record Candidate(long productId, String name, String url, String verdict, int met, int failed, int total, int unknown,
                            String reason, String reasonShort, String regStatus, String regNumber, List<Row> rows) {}
    public record Row(String mark, String text, String ours, String source) {}
}
