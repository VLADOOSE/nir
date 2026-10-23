package com.vladoose.nir.dto.request;

import java.math.BigDecimal;
import java.util.List;

/** «Курсы валют» — весь список одним PUT (спека §4.1); ourRate пусто — курс снят. */
public record CurrencyRatesRequest(List<Entry> rates) {
    public record Entry(String currency, BigDecimal ourRate) {}
}
