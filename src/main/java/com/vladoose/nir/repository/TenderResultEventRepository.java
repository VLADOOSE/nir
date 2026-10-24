package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ResultEventKind;
import com.vladoose.nir.entity.TenderResultEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

    Optional<TenderResultEvent> findFirstByTenderLotIdAndKindAndRefKey(Long tenderLotId, ResultEventKind kind, String refKey);

    Optional<TenderResultEvent> findFirstByTenderLotIdAndKindAndRefKeyIsNull(Long tenderLotId, ResultEventKind kind);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TenderResultEvent e set e.notifiedAt = :at where e.id in :ids")
    int markNotified(@Param("ids") Collection<Long> ids, @Param("at") OffsetDateTime at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TenderResultEvent e set e.digestedAt = :at where e.id in :ids")
    int markDigested(@Param("ids") Collection<Long> ids, @Param("at") OffsetDateTime at);

    /** Старше before — без сообщения: включили после перерыва — старьё не сыплется. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TenderResultEvent e set e.notifiedAt = :at where e.urgent = true and e.notifiedAt is null and e.createdAt < :before")
    int markStaleNotified(@Param("before") OffsetDateTime before, @Param("at") OffsetDateTime at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TenderResultEvent e set e.digestedAt = :at where e.urgent = false and e.digestedAt is null and e.createdAt < :before")
    int markStaleDigested(@Param("before") OffsetDateTime before, @Param("at") OffsetDateTime at);

    List<TenderResultEvent> findByTenderLotIdIn(Collection<Long> lotIds);

    List<TenderResultEvent> findByTenderIdInAndKindIn(Collection<Long> tenderIds, Collection<ResultEventKind> kinds);
}
