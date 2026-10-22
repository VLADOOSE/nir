package com.vladoose.nir.repository;

import com.vladoose.nir.entity.TenderLotResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TenderLotResultRepository extends JpaRepository<TenderLotResult, Long> {

    Optional<TenderLotResult> findByTenderLotId(Long tenderLotId);

    List<TenderLotResult> findByTenderId(Long tenderId);

    List<TenderLotResult> findByTenderIdIn(Collection<Long> tenderIds);

    List<TenderLotResult> findByTenderLotIdIn(Collection<Long> lotIds);

    /** Не состоялись, цепочка не досмотрена — проход ищет следующие попытки (спека §6). */
    @Query("select r from TenderLotResult r where r.outcome = com.vladoose.nir.entity.LotOutcome.FAILED and r.finalAt is null order by r.id")
    List<TenderLotResult> findOpenFailed();

    /** Состоялись, договора ещё нет. */
    @Query("select r from TenderLotResult r where r.outcome = com.vladoose.nir.entity.LotOutcome.DONE and r.contractNumber is null "
            + "and r.finalAt is null order by r.id")
    List<TenderLotResult> findDoneWithoutContract();
}
