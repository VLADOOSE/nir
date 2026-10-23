package com.vladoose.nir.repository;

import com.vladoose.nir.entity.OfficialRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface OfficialRateRepository extends JpaRepository<OfficialRate, Long> {
    /** Курс на дату не позже date — последний известный. */
    Optional<OfficialRate> findFirstBySourceAndCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(String source, String currency, LocalDate date);
    Optional<OfficialRate> findBySourceAndCurrencyAndRateDate(String source, String currency, LocalDate date);
}
