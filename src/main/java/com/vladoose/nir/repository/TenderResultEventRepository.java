package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ResultEventKind;
import com.vladoose.nir.entity.TenderResultEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TenderResultEventRepository extends JpaRepository<TenderResultEvent, Long> {

    /** «Звонить» и наша заявка, ещё не отправленные (тема не «никуда»). */
    @Query("select e from TenderResultEvent e where e.urgent = true and e.notifiedAt is null "
            + "and e.topic <> com.vladoose.nir.entity.ResultTopic.NONE order by e.id")
    List<TenderResultEvent> findUrgentPending();

    /** Сводка дня — только тема нашей продукции. */
    @Query("select e from TenderResultEvent e where e.urgent = false and e.digestedAt is null "
            + "and e.topic = com.vladoose.nir.entity.ResultTopic.PRODUCTS order by e.id")
    List<TenderResultEvent> findDigestPending();

    List<TenderResultEvent> findByTenderIdOrderByIdAsc(Long tenderId);

    boolean existsByTenderLotIdAndKind(Long tenderLotId, ResultEventKind kind);

    boolean existsByTenderLotIdAndKindAndRefKey(Long tenderLotId, ResultEventKind kind, String refKey);
}
