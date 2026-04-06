package com.vladoose.nir.repository;

import com.vladoose.nir.entity.TenderLot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenderLotRepository extends JpaRepository<TenderLot, Long> {

    List<TenderLot> findByTenderId(Long tenderId);
}
