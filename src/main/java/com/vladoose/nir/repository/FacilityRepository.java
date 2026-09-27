package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FacilityRepository extends JpaRepository<Facility, Long> {
    List<Facility> findByMarketAndMonitorTendersTrue(Market market);
    List<Facility> findByMarketAndRegionAndMonitorTendersTrue(Market market, String region);

    /**
     * Клиент по последним 10 цифрам телефона, в каком бы виде тот ни был введён.
     * Нативный SQL рыночным фильтром аспекта НЕ режется — рынок передаётся явно.
     */
    @Query(value = "SELECT * FROM facility WHERE market = :market AND phone IS NOT NULL "
            + "AND right(regexp_replace(phone, '\\D', '', 'g'), 10) = :last10", nativeQuery = true)
    List<Facility> findByMarketAndPhoneLast10(@Param("market") String market, @Param("last10") String last10);

    List<Facility> findByMarketAndEmailIgnoreCase(Market market, String email);
}
