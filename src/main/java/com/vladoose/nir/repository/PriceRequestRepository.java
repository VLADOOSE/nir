package com.vladoose.nir.repository;

import com.vladoose.nir.entity.LetterStatus;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.PriceRequest;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface PriceRequestRepository extends JpaRepository<PriceRequest, Long> {

    List<PriceRequest> findByTenderId(Long tenderId);

    /** Запрос «передам сам», ждущий подтверждения (бот, «📱 Отправлю сам»): CREATED, без времени отправки. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PriceRequest p SET p.status = 'CREATED', p.sentAt = NULL, p.letterStatus = :manual "
            + "WHERE p.id = :id AND p.letterStatus IN :failed")
    int markManualPending(@Param("id") Long id, @Param("manual") LetterStatus manual, @Param("failed") Collection<LetterStatus> failed);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PriceRequest p SET p.status = 'SENT', p.sentAt = :at WHERE p.id = :id AND p.status = 'CREATED' AND p.letterStatus = :manual")
    int confirmManual(@Param("id") Long id, @Param("at") OffsetDateTime at, @Param("manual") LetterStatus manual);

    /** Строки запроса и ссылки на него убирает база (ON DELETE CASCADE / SET NULL). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PriceRequest p WHERE p.id = :id AND p.status = 'CREATED' AND p.letterStatus = :manual")
    int deletePendingManual(@Param("id") Long id, @Param("manual") LetterStatus manual);

    /** Лента и этапы сделки: поставщик и строки — одним запросом (findByTenderId тянет их выборкой на каждый запрос). */
    @EntityGraph(attributePaths = {"distributor", "items", "items.tenderLot", "items.medEquipment"})
    @Query("SELECT pr FROM PriceRequest pr WHERE pr.tender.id = :tenderId")
    List<PriceRequest> findForDealByTenderId(@Param("tenderId") Long tenderId);

    List<PriceRequest> findByDistributorId(Long distributorId);

    List<PriceRequest> findByStatus(String status);

    @Query("SELECT pr.distributor.name, COUNT(pr), " +
           "SUM(CASE WHEN i.responsePrice IS NOT NULL THEN 1 ELSE 0 END), " +
           "AVG(i.responsePrice) " +
           "FROM PriceRequest pr LEFT JOIN pr.items i " +
           "GROUP BY pr.distributor.name")
    List<Object[]> distributorPriceRequestStats();

    // Воронка: тендеры с ≥1 КП (REQUESTED). Market явным параметром (PriceRequest market-scoped, но единообразно).
    @Query("SELECT DISTINCT pr.tender.id FROM PriceRequest pr WHERE pr.market = :market")
    List<Long> findTenderIdsWithPriceRequest(@Param("market") Market market);

    // Воронка: тендеры с ≥1 введённой ценой (PRICED). PriceRequestItem НЕ market-scoped → фильтр по pr.market явно.
    @Query("SELECT DISTINCT pri.priceRequest.tender.id FROM PriceRequestItem pri "
            + "WHERE pri.responsePrice IS NOT NULL AND pri.priceRequest.market = :market")
    List<Long> findTenderIdsWithResponsePrice(@Param("market") Market market);

    /** Для этапа заявки: (заявка, поставщик, статус запроса). */
    @Query("SELECT pr.tender.id, pr.distributor.id, pr.status FROM PriceRequest pr WHERE pr.tender.id IN :ids")
    List<Object[]> findStageRows(@Param("ids") Collection<Long> ids);

    /** Последний запрос цен поставщику по лотам тендера — «уже отправлено из АИС» у письма из бота (спека §7). */
    @Query("SELECT max(p.createdAt) FROM PriceRequest p JOIN p.items i WHERE p.tender.id = :tenderId "
            + "AND p.distributor.id = :distId AND i.tenderLot.id IN :lotIds")
    OffsetDateTime lastCreatedAt(@Param("tenderId") Long tenderId, @Param("distId") Long distId,
                                 @Param("lotIds") Collection<Long> lotIds);
}
