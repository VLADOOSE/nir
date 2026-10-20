package com.vladoose.nir.repository;

import com.vladoose.nir.entity.TenderLotProductMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TenderLotProductMatchRepository extends JpaRepository<TenderLotProductMatch, Long> {
    List<TenderLotProductMatch> findByTenderId(Long tenderId);
    List<TenderLotProductMatch> findByTenderIdIn(Collection<Long> tenderIds);
    List<TenderLotProductMatch> findByLotId(Long lotId);
    boolean existsByTenderId(Long tenderId);

    /**
     * Лоты с отметкой «наш товар», чьё ТЗ ещё не сверено (спека 2026-10-19-product-watch §7.4): тендер ACTIVE, статус ТЗ не
     * терминальный, попыток меньше 3; сначала ближайший срок. Без токена goszakup — только лоты СК-Фармации.
     */
    @Query(value = """
            SELECT DISTINCT ON (l.id) l.id
            FROM tender_lot_product_match m
            JOIN tender t ON t.id = m.tender_id
            JOIN tender_lot l ON l.id = m.lot_id
            WHERE m.spec_checked_at IS NULL AND t.status = 'ACTIVE' AND t.market = 'KZ'
              AND (t.platform = 'SK_PHARMACY' OR (:goszakup AND (t.platform = 'GOSZAKUP' OR t.platform IS NULL)))
              AND (l.tech_spec_status IS NULL OR l.tech_spec_status IN ('PENDING', 'ERROR', 'OK'))
              AND l.tech_spec_attempts < 3
            ORDER BY l.id, t.deadline_at NULLS LAST
            """, nativeQuery = true)
    List<Long> findLotsForSpecCheckUnordered(@Param("goszakup") boolean goszakup);
}
