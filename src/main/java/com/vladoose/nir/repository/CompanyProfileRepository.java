package com.vladoose.nir.repository;

import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.Market;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyProfileRepository extends JpaRepository<CompanyProfile, Long> {
    Optional<CompanyProfile> findByMarket(Market market);
}
