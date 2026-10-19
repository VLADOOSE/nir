package com.vladoose.nir.repository;

import com.vladoose.nir.entity.DealPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DealPaymentRepository extends JpaRepository<DealPayment, Long> {
    List<DealPayment> findByTenderIdIn(Collection<Long> tenderIds);
    boolean existsByDealSupplierId(Long dealSupplierId);
    boolean existsByTenderIdAndDealSupplierIdIsNotNull(Long tenderId);
}
