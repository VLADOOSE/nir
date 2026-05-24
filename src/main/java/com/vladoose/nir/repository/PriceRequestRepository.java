package com.vladoose.nir.repository;

import com.vladoose.nir.entity.PriceRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
