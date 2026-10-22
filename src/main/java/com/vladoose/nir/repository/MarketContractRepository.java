package com.vladoose.nir.repository;

import com.vladoose.nir.entity.MarketContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface MarketContractRepository extends JpaRepository<MarketContract, Long> {

    List<MarketContract> findByLotExtIdIn(Collection<Long> lotExtIds);

    List<MarketContract> findByEnstruCodeAndSignedAtGreaterThanEqual(String enstruCode, LocalDate from);

    List<MarketContract> findByCustomerBinAndSignedAtGreaterThanEqualOrderBySignedAtDesc(String customerBin, LocalDate from);

    /** Категории со словом (pattern — «%слово%» нижним регистром): код, название, описание, число договоров. */
    @Query(value = """
            SELECT enstru_code, MAX(name), MAX(description), COUNT(*)
            FROM market_contract
            WHERE enstru_code IS NOT NULL AND signed_at >= :from
              AND (LOWER(name) LIKE :pattern OR LOWER(COALESCE(description, '')) LIKE :pattern
                   OR LOWER(COALESCE(extra_desc, '')) LIKE :pattern)
            GROUP BY enstru_code
            ORDER BY COUNT(*) DESC
            LIMIT 20
            """, nativeQuery = true)
    List<Object[]> findCategories(@Param("pattern") String pattern, @Param("from") LocalDate from);
}
