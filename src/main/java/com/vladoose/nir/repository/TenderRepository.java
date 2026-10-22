package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TenderRepository extends JpaRepository<Tender, Long> {

    boolean existsByFacilityId(Long facilityId);

    List<Tender> findBySource(Source source);

    Optional<Tender> findBySourceExtId(String sourceExtId);

    /** Заведённые поиском нашей продукции по стране — обновляются им же (спека 2026-10-19-product-watch §7.3). */
    List<Tender> findByFoundByProductSearchTrueAndStatus(String status);

    /** Тендеры площадок с идущим приёмом — для пересчёта отметок «наш товар» (спека 2026-10-19-product-watch §8). */
    @Query("select t.id from Tender t where t.market = :market and t.status = 'ACTIVE' and t.source = com.vladoose.nir.entity.Source.PUBLIC_TENDER")
    List<Long> findOpenIds(@Param("market") Market market);

    boolean existsBySourceExtId(String sourceExtId);

    List<Tender> findByStatusAndDeadlineBefore(String status, LocalDate deadline);

    List<Tender> findByStatusAndDeadlineAtLessThanEqual(String status, LocalDateTime deadlineAt);

    @Query("SELECT DISTINCT t FROM Tender t JOIN t.lots l WHERE " +
           "t.source = com.vladoose.nir.entity.Source.PUBLIC_TENDER AND " +
           "(:status IS NULL OR t.status = :status) AND " +
           "(:facilityId IS NULL OR t.facility.id = :facilityId) AND " +
           "(:equipType IS NULL OR l.equipmentType.name = :equipType) AND " +
           "(:minCost IS NULL OR t.totalCost >= :minCost) AND " +
           "(:maxCost IS NULL OR t.totalCost <= :maxCost) AND " +
           "(:dateFrom IS NULL OR t.deadline >= :dateFrom) AND " +
           "(:dateTo IS NULL OR t.deadline <= :dateTo)")
    List<Tender> searchTenders(@Param("status") String status,
                               @Param("facilityId") Long facilityId,
                               @Param("equipType") String equipType,
                               @Param("minCost") BigDecimal minCost,
                               @Param("maxCost") BigDecimal maxCost,
                               @Param("dateFrom") LocalDate dateFrom,
                               @Param("dateTo") LocalDate dateTo);

    /** Номера частных заявок рынка по шаблону «ЧЗ-<год>-%» (HQL — рынок режет аспект). */
    @Query("SELECT t.tenderNumber FROM Tender t WHERE t.source = com.vladoose.nir.entity.Source.PRIVATE_REQUEST "
         + "AND t.tenderNumber LIKE :pattern")
    List<String> findPrivateNumbers(@Param("pattern") String pattern);

    /** Частные заявки рынка в статусах — новые сверху (у заявок до V26 дата может быть пустой — они в конце). */
    @Query("SELECT t FROM Tender t WHERE t.source = com.vladoose.nir.entity.Source.PRIVATE_REQUEST "
         + "AND t.status IN :statuses ORDER BY t.createdAt DESC NULLS LAST, t.id DESC")
    List<Tender> findPrivate(@Param("statuses") Collection<String> statuses, Pageable pageable);

    /** Номера заявок для журнала КП («из заявки ЧЗ-…») — одним запросом. */
    @Query("select t.id, t.tenderNumber from Tender t where t.id in :ids")
    List<Object[]> findNumbersByIds(@Param("ids") Collection<Long> ids);

    List<Tender> findBySourceExtIdIn(Collection<String> sourceExtIds);

    /** Разобранное ТЗ лота, если тендер уже в АИС (по номеру объявления и номеру лота площадки). */
    @Query("select l.requiredSpec from TenderLot l where l.tender.sourceExtId = :anno and l.sourceLotCode = :code"
            + " and l.techSpecStatus = com.vladoose.nir.entity.TechSpecStatus.OK")
    List<String> findParsedLotSpec(@Param("anno") String anno, @Param("code") String code);

    /**
     * Тендеры для прохода итогов (спека §4): KZ goszakup, срок прошёл (время площадки; только дата — весь день) не раньше
     * from, есть лот без окончательного итога. Новые сроки — первыми.
     */
    @Query(value = """
            SELECT t.id FROM tender t
            WHERE t.market = 'KZ' AND t.platform = 'GOSZAKUP' AND t.source = 'PUBLIC_TENDER' AND t.source_ext_id IS NOT NULL
              AND COALESCE(t.deadline_at, CAST(t.deadline + 1 AS timestamp)) <= :now
              AND COALESCE(t.deadline_at, CAST(t.deadline + 1 AS timestamp)) >= :from
              AND EXISTS (SELECT 1 FROM tender_lot l WHERE l.tender_id = t.id AND l.source_removed_at IS NULL
                          AND NOT EXISTS (SELECT 1 FROM tender_lot_result r WHERE r.tender_lot_id = l.id AND r.final_at IS NOT NULL))
            ORDER BY COALESCE(t.deadline_at, CAST(t.deadline + 1 AS timestamp)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findResultCandidates(@Param("now") LocalDateTime now, @Param("from") LocalDateTime from, @Param("limit") int limit);
}
