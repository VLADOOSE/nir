package com.vladoose.nir.repository;

import com.vladoose.nir.entity.EquipmentTypeSynonym;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentTypeSynonymRepository extends JpaRepository<EquipmentTypeSynonym, Long> {
    boolean existsByTermNorm(String termNorm);
}
