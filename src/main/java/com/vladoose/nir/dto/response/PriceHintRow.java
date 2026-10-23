package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Строка прайса в подсказке, поиске и «Поставщик ▾» (спека 2026-10-23 §6, решение плана 15). */
public record PriceHintRow(Long rowId, Long priceListId, Long distributorId, String supplierName, boolean manufacturer,
                           String section, String name, String model, String article, LocalDate listDate, LocalDate validTo,
                           boolean expired, String currency, BigDecimal price, BigDecimal ourRate, BigDecimal converted,
                           BigDecimal today, LocalDate todayDate, String terms, String regText, String regNumber,
                           String regStatus, LocalDate regExpires) {}
