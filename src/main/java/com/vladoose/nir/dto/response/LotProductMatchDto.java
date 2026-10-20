package com.vladoose.nir.dto.response;

import java.util.List;

/** Отметка «наш товар» лота для карточки тендера (спека 2026-10-19-product-watch §12.3): ссылки — на товары сайта. */
public record LotProductMatchDto(String groupName, String word, String level, String evidence, String productName, String productUrl,
                                 List<Similar> similar) {
    public record Similar(String name, String brand, String url) {}
}
