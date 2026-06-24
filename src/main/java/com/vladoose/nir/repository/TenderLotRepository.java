package com.vladoose.nir.repository;

import com.vladoose.nir.entity.TenderLot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TenderLotRepository extends JpaRepository<TenderLot, Long> {

    List<TenderLot> findByTenderId(Long tenderId);

    List<TenderLot> findByEquipmentTypeId(Long equipmentTypeId);

    @Query("SELECT l.equipmentType.name, COUNT(l) FROM TenderLot l WHERE l.equipmentType IS NOT NULL AND l.tender.source = com.vladoose.nir.entity.Source.PUBLIC_TENDER GROUP BY l.equipmentType.name ORDER BY COUNT(l) DESC")
    List<Object[]> countByEquipType();
}
