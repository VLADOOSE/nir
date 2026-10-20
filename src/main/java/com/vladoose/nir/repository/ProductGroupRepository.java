package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductGroupRepository extends JpaRepository<ProductGroup, Long> {
    List<ProductGroup> findByMarketOrderByPositionAscIdAsc(Market market);
    List<ProductGroup> findByMarketAndActiveTrueOrderByPositionAscIdAsc(Market market);
}
