package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Ответ поставщика с ценой на позицию строки КП — пункт «Поставщик ▾» (спека 2026-10-12 §8). */
public record SupplierOfferResponse(Long priceRequestItemId, String supplierName, BigDecimal price, LocalDate respondedOn,
                                    boolean accepted, boolean cheapest, boolean selected) {}
