package com.vladoose.nir.repository;

import com.vladoose.nir.entity.MarketLookup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketLookupRepository extends JpaRepository<MarketLookup, String> {
}
