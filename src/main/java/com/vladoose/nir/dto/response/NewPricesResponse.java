package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** Плашка «Пришли новые цены по N позициям» (спека 2026-10-12 §8): строки черновика, которым есть что подставить. */
public record NewPricesResponse(List<Line> lines) {
    public record Line(Long itemId, int lineNo, String name, BigDecimal purchasePrice, BigDecimal newPrice,
                       String supplierName) {}
}
