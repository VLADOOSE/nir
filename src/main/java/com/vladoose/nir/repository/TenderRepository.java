package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Tender;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface TenderRepository extends JpaRepository<Tender, Long> {

    boolean existsByFacilityId(Long facilityId);

    @Query("SELECT DISTINCT t FROM Tender t JOIN t.lots l WHERE " +
           "(:status IS NULL OR t.status = :status) AND " +
           "(:facilityId IS NULL OR t.facility.id = :facilityId) AND " +
           "(:equipType IS NULL OR l.equipType = :equipType) AND " +
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
}
