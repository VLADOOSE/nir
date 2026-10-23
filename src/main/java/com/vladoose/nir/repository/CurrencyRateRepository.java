package com.vladoose.nir.repository;

import com.vladoose.nir.entity.CurrencyRate;
import com.vladoose.nir.entity.Market;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CurrencyRateRepository extends JpaRepository<CurrencyRate, Long> {
    List<CurrencyRate> findByMarket(Market market);
    Optional<CurrencyRate> findByMarketAndCurrency(Market market, String currency);
}
