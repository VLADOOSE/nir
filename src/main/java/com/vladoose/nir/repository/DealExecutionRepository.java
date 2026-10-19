package com.vladoose.nir.repository;

import com.vladoose.nir.entity.DealExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DealExecutionRepository extends JpaRepository<DealExecution, Long> {
    List<DealExecution> findByTenderIdIn(Collection<Long> tenderIds);
}
