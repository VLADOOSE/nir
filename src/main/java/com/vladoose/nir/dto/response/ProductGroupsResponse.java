package com.vladoose.nir.dto.response;

import com.vladoose.nir.service.productwatch.ProductWatchScheduler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Страница «Наша продукция»: kz — рынок West-Med (на РФ блока нет), группы, каталог сайта, состояние прогонов. */
public record ProductGroupsResponse(boolean kz, List<ProductGroupDto> groups, Catalog catalog, ProductWatchScheduler.Status watch) {
    /** Каталог сайта: число товаров, по брендам, когда обновлён, устарел ли (старше двух суток). */
    public record Catalog(int count, Map<String, Integer> byBrand, OffsetDateTime syncedAt, boolean stale) {}
}
