package com.vladoose.nir.dto.response;

import java.util.List;

/** Группа «нашей продукции» на странице (спека 2026-10-19-product-watch §12.1). brandGroup — признак сида, страница его не меняет. */
public record ProductGroupDto(Long id, String name, List<String> searchWords, List<String> requireWords, List<String> excludeWords,
                              List<String> brandWords, boolean active, boolean brandGroup) {}
