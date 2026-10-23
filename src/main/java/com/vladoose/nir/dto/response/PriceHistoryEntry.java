package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Запись истории цен (спека §7): прайс или ответ на запрос цен; цена — в исходной валюте; pct — к прошлой записи. */
public record PriceHistoryEntry(String kind, LocalDate date, String supplierName, String currency, BigDecimal price,
                                BigDecimal pct, Long priceListId, Long priceRequestId) {}
