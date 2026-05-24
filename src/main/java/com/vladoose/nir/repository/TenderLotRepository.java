package com.vladoose.nir.repository;

import com.vladoose.nir.entity.TenderLot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TenderLotRepository extends JpaRepository<TenderLot, Long> {

    List<TenderLot> findByTenderId(Long tenderId);

    @Query("SELECT l.equipType, COUNT(l) FROM TenderLot l GROUP BY l.equipType ORDER BY COUNT(l) DESC")
    List<Object[]> countByEquipType();
}
