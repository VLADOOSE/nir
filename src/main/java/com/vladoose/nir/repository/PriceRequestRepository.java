package com.vladoose.nir.repository;

import com.vladoose.nir.entity.PriceRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PriceRequestRepository extends JpaRepository<PriceRequest, Long> {

    List<PriceRequest> findByTenderLotId(Long tenderLotId);

    List<PriceRequest> findByDistributorId(Long distributorId);

    List<PriceRequest> findByStatus(String status);

    @Query("SELECT pr.distributor.name, COUNT(pr), " +
           "SUM(CASE WHEN pr.responsePrice IS NOT NULL THEN 1 ELSE 0 END), " +
           "AVG(pr.responsePrice) " +
           "FROM PriceRequest pr GROUP BY pr.distributor.name")
    List<Object[]> distributorPriceRequestStats();
}
