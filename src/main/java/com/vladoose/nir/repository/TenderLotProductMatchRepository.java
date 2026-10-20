package com.vladoose.nir.repository;

import com.vladoose.nir.entity.TenderLotProductMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TenderLotProductMatchRepository extends JpaRepository<TenderLotProductMatch, Long> {
    List<TenderLotProductMatch> findByTenderId(Long tenderId);
    List<TenderLotProductMatch> findByTenderIdIn(Collection<Long> tenderIds);
    List<TenderLotProductMatch> findByLotId(Long lotId);
    boolean existsByTenderId(Long tenderId);
}
