package com.vladoose.nir.dto.response;

import com.vladoose.nir.service.market.MarketCategories.Category;
import com.vladoose.nir.service.market.MarketStats;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Цена рынка (спека 2026-10-22-tender-results-market-prices §13): категория и другие найденные, единица, расчёт, строки. */
public record MarketPriceResponse(Category category, List<Category> alternatives, String unit, List<String> units, MarketStats stats,
                                  Bids bids, List<Row> rows, OffsetDateTime fetchedAt, boolean stale, String error) {

    /** Цены участников разобранных протоколов той же категории и единицы. */
    public record Bids(int count, BigDecimal median) {}

    public record Row(LocalDate signedAt, String region, String customer, BigDecimal quantity, String unit, BigDecimal unitPrice,
                      BigDecimal budgetUnitPrice, String supplierName, String anno, String name) {}
}
