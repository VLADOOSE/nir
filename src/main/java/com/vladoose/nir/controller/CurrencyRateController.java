package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.dto.response.CurrencyRatesResponse;
import com.vladoose.nir.service.rates.CurrencyRates;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** «Система → Курсы валют» (спека §4.1, §9): читать — все, менять — администратор. */
@RestController
@RequestMapping("/api/currency-rates")
public class CurrencyRateController {

    private final CurrencyRates rates;

    public CurrencyRateController(CurrencyRates rates) {
        this.rates = rates;
    }

    @GetMapping
    public CurrencyRatesResponse get() {
        return rates.view(MarketContext.get());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public CurrencyRatesResponse save(@RequestBody CurrencyRatesRequest req) {
        return rates.save(MarketContext.get(), req, CurrentUser.name());
    }
}
