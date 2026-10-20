package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ProcurementPlanMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ProcurementPlanMatchRepository extends JpaRepository<ProcurementPlanMatch, Long> {
    List<ProcurementPlanMatch> findByPlanPointIdIn(Collection<Long> planPointIds);
    void deleteByPlanPointId(Long planPointId);
}
