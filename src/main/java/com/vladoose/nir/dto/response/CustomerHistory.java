package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Договоры заказчика за 2 года (спека 2026-10-22-tender-results-market-prices §8): сначала той же категории, потом медизделия. */
public record CustomerHistory(String bin, List<Row> rows, OffsetDateTime fetchedAt, String error) {
    public record Row(LocalDate signedAt, String name, BigDecimal quantity, String unit, BigDecimal unitPrice, String supplierName,
                      String region, boolean sameCategory, String anno) {}
}
