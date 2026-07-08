package com.vladoose.nir.repository;

import com.vladoose.nir.entity.EquipmentTypeSynonym;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EquipmentTypeSynonymRepository extends JpaRepository<EquipmentTypeSynonym, Long> {
    boolean existsByTermNorm(String termNorm);

    Optional<EquipmentTypeSynonym> findByTermNorm(String termNorm);
}
