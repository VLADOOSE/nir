package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/market")
public class MarketController {

    @GetMapping("/current")
    public Map<String, String> current() {
        Market m = MarketContext.get();
        return Map.of(
                "market", m.name(),
                "currencyCode", m.currencyCode(),
                "currencySymbol", m.currencySymbol(),
                "companyShortName", m.companyShortName());
    }
}
