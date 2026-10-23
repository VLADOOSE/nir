package com.vladoose.nir.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Вкладка «Наша продукция → Товары» (спека §8.2): товары каталога рынка KZ с РУ. */
public record ProductCatalogResponse(boolean kz, OffsetDateTime checkedAt, List<Item> items) {
    public record Item(long id, String name, String brand, String url, String regNumber, String regStatus, LocalDate regExpires,
                       boolean regUnlimited, int attributesCount, PriceHint priceList) {}
}
