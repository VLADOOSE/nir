package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.SiteProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SiteProductRepository extends JpaRepository<SiteProduct, Long> {
    List<SiteProduct> findByMarketAndRemovedAtIsNull(Market market);
    List<SiteProduct> findByMarket(Market market);
    Optional<SiteProduct> findBySiteId(UUID siteId);
}
