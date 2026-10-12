package com.vladoose.nir.repository;

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
}
