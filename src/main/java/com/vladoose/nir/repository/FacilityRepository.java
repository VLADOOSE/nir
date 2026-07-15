package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FacilityRepository extends JpaRepository<Facility, Long> {
    List<Facility> findByMarketAndMonitorTendersTrue(Market market);
    List<Facility> findByMarketAndRegionAndMonitorTendersTrue(Market market, String region);
}
