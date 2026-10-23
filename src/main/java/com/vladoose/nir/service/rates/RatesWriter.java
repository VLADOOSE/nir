package com.vladoose.nir.service.rates;

import com.vladoose.nir.entity.OfficialRate;
import com.vladoose.nir.integration.nbrk.NbrkRate;
import com.vladoose.nir.repository.OfficialRateRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** Запись курсов НБ РК на дату (отдельный бин — сеть вне транзакции, CLAUDE.md §6); курс на ту же дату перезаписывается. */
@Component
public class RatesWriter {

    static final Set<String> WANTED = Set.of("RUB", "USD", "EUR", "CNY");

    private final OfficialRateRepository repo;

    public RatesWriter(OfficialRateRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public int store(LocalDate date, List<NbrkRate> rates, OffsetDateTime now) {
        int n = 0;
        for (NbrkRate r : rates) {
            if (!WANTED.contains(r.currency())) continue;
            OfficialRate row = repo.findBySourceAndCurrencyAndRateDate(CurrencyRates.NBRK, r.currency(), date)
                    .orElseGet(() -> OfficialRate.builder().source(CurrencyRates.NBRK).currency(r.currency()).rateDate(date).build());
            row.setRate(r.rate());
            row.setFetchedAt(now);
            repo.save(row);
            n++;
        }
        return n;
    }
}
