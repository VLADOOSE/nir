package com.vladoose.nir.repository;

import com.vladoose.nir.entity.DealSupplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DealSupplierRepository extends JpaRepository<DealSupplier, Long> {
    List<DealSupplier> findByTenderIdIn(Collection<Long> tenderIds);
    Optional<DealSupplier> findByTenderIdAndDistributorId(Long tenderId, Long distributorId);
    boolean existsByTenderIdAndOrderedOnIsNotNull(Long tenderId);
    boolean existsByTenderIdAndShippedOnIsNotNull(Long tenderId);
}
