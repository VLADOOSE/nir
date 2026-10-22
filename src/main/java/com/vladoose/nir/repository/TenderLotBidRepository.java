package com.vladoose.nir.repository;

import com.vladoose.nir.entity.TenderLotBid;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface TenderLotBidRepository extends JpaRepository<TenderLotBid, Long> {

    List<TenderLotBid> findByResultIdOrderByPlaceAscIdAsc(Long resultId);

    List<TenderLotBid> findByResultIdIn(Collection<Long> resultIds);

    @Modifying
    @Query("delete from TenderLotBid b where b.resultId = :resultId")
    void deleteByResultId(@Param("resultId") Long resultId);

    /** Цены участников протоколов категории (спека §9): цена за единицу и единица пункта плана лота. */
    @Query(value = """
            SELECT b.unit_price, l.plan_unit FROM tender_lot_bid b
            JOIN tender_lot_result r ON r.id = b.result_id
            JOIN tender_lot l ON l.id = r.tender_lot_id
            WHERE l.enstru_code = :code AND b.rejected = false AND b.unit_price IS NOT NULL
              AND COALESCE(r.results_at, CAST(r.created_at AS timestamp)) >= :from
            """, nativeQuery = true)
    List<Object[]> findBidPrices(@Param("code") String code, @Param("from") LocalDateTime from);
}
