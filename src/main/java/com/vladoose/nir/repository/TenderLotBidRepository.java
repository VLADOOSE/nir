package com.vladoose.nir.repository;

import com.vladoose.nir.entity.TenderLotBid;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TenderLotBidRepository extends JpaRepository<TenderLotBid, Long> {

    List<TenderLotBid> findByResultIdOrderByPlaceAscIdAsc(Long resultId);

    List<TenderLotBid> findByResultIdIn(Collection<Long> resultIds);

    @Modifying
    @Query("delete from TenderLotBid b where b.resultId = :resultId")
    void deleteByResultId(@Param("resultId") Long resultId);
}
