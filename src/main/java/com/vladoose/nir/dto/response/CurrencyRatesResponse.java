package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * «Система → Курсы валют» (спека §4.1): по валюте рынка — наш курс, курс НБ РК сегодня (KZ), запас в %. todayEnabled —
 * курс подтягивается (RATES_NBRK_ENABLED).
 */
public record CurrencyRatesResponse(String market, String marketCurrency, boolean todayEnabled, List<Row> rates) {
    public record Row(String currency, BigDecimal ourRate, String updatedBy, OffsetDateTime updatedAt, Today today, BigDecimal marginPct) {}
    public record Today(BigDecimal rate, LocalDate date, boolean stale) {}
}
