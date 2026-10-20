package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ProcurementPlanPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Рыночная сущность: аспект включает фильтр рынка (CLAUDE.md §6). */
public interface ProcurementPlanPointRepository extends JpaRepository<ProcurementPlanPoint, Long> {
    Optional<ProcurementPlanPoint> findByExtId(Long extId);
    List<ProcurementPlanPoint> findByGoneAtIsNull();
}
