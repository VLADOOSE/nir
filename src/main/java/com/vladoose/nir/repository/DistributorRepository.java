package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Distributor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DistributorRepository extends JpaRepository<Distributor, Long> {

    @Query("SELECT DISTINCT d FROM Distributor d LEFT JOIN d.equipmentTypes et " +
           "WHERE et.id = :typeId OR SIZE(d.equipmentTypes) = 0")
    List<Distributor> findEligibleForType(@Param("typeId") Long typeId);
}
