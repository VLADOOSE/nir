package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.PriceRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PriceRequestRepository extends JpaRepository<PriceRequest, Long> {

    List<PriceRequest> findByTenderId(Long tenderId);

    List<PriceRequest> findByDistributorId(Long distributorId);

    List<PriceRequest> findByStatus(String status);

    @Query("SELECT pr.distributor.name, COUNT(pr), " +
           "SUM(CASE WHEN i.responsePrice IS NOT NULL THEN 1 ELSE 0 END), " +
           "AVG(i.responsePrice) " +
           "FROM PriceRequest pr LEFT JOIN pr.items i " +
           "GROUP BY pr.distributor.name")
    List<Object[]> distributorPriceRequestStats();

    // Воронка: тендеры с ≥1 КП (REQUESTED). Market явным параметром (PriceRequest market-scoped, но единообразно).
    @Query("SELECT DISTINCT pr.tender.id FROM PriceRequest pr WHERE pr.market = :market")
    List<Long> findTenderIdsWithPriceRequest(@Param("market") Market market);

    // Воронка: тендеры с ≥1 введённой ценой (PRICED). PriceRequestItem НЕ market-scoped → фильтр по pr.market явно.
    @Query("SELECT DISTINCT pri.priceRequest.tender.id FROM PriceRequestItem pri "
            + "WHERE pri.responsePrice IS NOT NULL AND pri.priceRequest.market = :market")
    List<Long> findTenderIdsWithResponsePrice(@Param("market") Market market);
}
