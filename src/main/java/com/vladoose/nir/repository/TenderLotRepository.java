package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.AiVerdict;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TenderPlatform;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface TenderLotRepository extends JpaRepository<TenderLot, Long> {

    /** Модели в работе лотов тендеров — одним запросом на список (`[tenderId, имя модели]`, порядок лотов). */
    @Query("SELECT l.tender.id, e.name FROM TenderLot l JOIN l.proposedEquipment e WHERE l.tender.id IN :ids ORDER BY l.id")
    List<Object[]> findModelsByTenderIds(@Param("ids") Collection<Long> ids);

    List<TenderLot> findByTenderId(Long tenderId);

    /** Тендеры списка, у лота которых этот вывод ИИ — одним запросом (фильтр «🔁 Аналог (по ИИ)»). */
    @Query("SELECT DISTINCT l.tender.id FROM TenderLot l WHERE l.tender.id IN :ids AND l.aiVerdict = :verdict")
    List<Long> findTenderIdsByAiVerdict(@Param("ids") Collection<Long> ids, @Param("verdict") AiVerdict verdict);

    /** Лоты нескольких тендеров одним запросом — для фоновых потоков без сессии (ленивую Tender.lots там не читать). */
    List<TenderLot> findByTenderIdIn(Collection<Long> tenderIds);

    /** Лоты карточки — по номеру (без номера — в конце), затем по id: без порядка UPDATE уносил бы лот в конец списка. */
    List<TenderLot> findByTenderIdOrderByLotNumberAscIdAsc(Long tenderId);

    List<TenderLot> findByEquipmentTypeId(Long equipmentTypeId);

    /**
     * Лоты в очереди на фоновый разбор техспеки. Рынок передаётся ЯВНО: воркер — фоновый поток,
     * привязанной сессии у него нет, и рыночный аспект не сработает (CLAUDE.md §6).
     */
    @Query("SELECT l.id FROM TenderLot l WHERE l.techSpecStatus = 'PENDING' "
         + "AND l.tender.market = :market ORDER BY l.id DESC")
    List<Long> findPendingTechSpec(@Param("market") Market market, Pageable pageable);

    /**
     * То же, но только по одной площадке. Нужно, когда качать вторую нечем: без токена goszakup
     * его лоты нельзя ни разобрать, ни честно списать — иначе один незаданный env навсегда
     * пометил бы 1300 лотов как «файла нет».
     */
    @Query("SELECT l.id FROM TenderLot l WHERE l.techSpecStatus = 'PENDING' "
         + "AND l.tender.market = :market AND l.tender.platform = :platform ORDER BY l.id DESC")
    List<Long> findPendingTechSpecByPlatform(@Param("market") Market market,
                                            @Param("platform") TenderPlatform platform,
                                            Pageable pageable);

    /**
     * Вернуть в очередь лоты, сгоревшие на недоступности площадки давнее {@code before}:
     * ERROR — это состояние ВНЕШНЕГО МИРА (IP-блок, обрыв сети), а не свойство лота, поэтому
     * конечным быть не должно. Зовётся через {@link com.vladoose.nir.service.TechSpecStatusWriter}
     * (отдельный бин с транзакцией — фоновому потоку нужна привязанная сессия, §6).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TenderLot l SET l.techSpecStatus = 'PENDING' "
         + "WHERE l.techSpecStatus = 'ERROR' AND l.techSpecAttemptedAt < :before")
    int requeueStaleErrors(@Param("before") OffsetDateTime before);

    @Query("SELECT l.equipmentType.name, COUNT(l) FROM TenderLot l WHERE l.equipmentType IS NOT NULL AND l.tender.source = com.vladoose.nir.entity.Source.PUBLIC_TENDER GROUP BY l.equipmentType.name ORDER BY COUNT(l) DESC")
    List<Object[]> countByEquipType();

    @Query("SELECT l.tender.id, COUNT(l) FROM TenderLot l WHERE l.tender.id IN :ids GROUP BY l.tender.id")
    List<Object[]> countByTenderIds(@Param("ids") Collection<Long> ids);

    /** Частные заявки, у которых наименование или бренд позиции подходит под шаблон LIKE (с экранированием \). */
    @Query("SELECT DISTINCT l.tender.id FROM TenderLot l WHERE l.tender.source = com.vladoose.nir.entity.Source.PRIVATE_REQUEST "
         + "AND (LOWER(l.equipName) LIKE :pattern ESCAPE '\\' OR LOWER(l.manufact) LIKE :pattern ESCAPE '\\')")
    List<Long> findPrivateTenderIdsByLineText(@Param("pattern") String pattern);

    /** Лоты всех попыток закупок по пунктам плана (наследование «наш лот», спека §7). */
    List<TenderLot> findByPlanPointIdIn(Collection<Long> planPointIds);

    /**
     * Наши лоты (спека 2026-10-22-tender-results-market-prices §7): модель в работе, запрос цен, позиция заявки на участие —
     * или отметка «наш товар» в тендере, о котором была пачка, если ИИ не ответил «не наше» / «аналог».
     */
    @Query(value = """
            SELECT l.id FROM tender_lot l JOIN tender t ON t.id = l.tender_id
            WHERE l.id IN (:ids) AND (
                l.proposed_equipment_id IS NOT NULL
                OR EXISTS (SELECT 1 FROM price_request_item i WHERE i.tender_lot_id = l.id)
                OR EXISTS (SELECT 1 FROM apply_item a WHERE a.tender_lot_id = l.id)
                OR (t.product_notified_level IS NOT NULL
                    AND EXISTS (SELECT 1 FROM tender_lot_product_match m WHERE m.lot_id = l.id)
                    AND COALESCE(l.ai_verdict, '') NOT IN ('NO', 'ANALOG')))
            """, nativeQuery = true)
    List<Long> findOurLotIds(@Param("ids") Collection<Long> ids);
}
