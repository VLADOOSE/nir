package com.vladoose.nir.repository;

import com.vladoose.nir.entity.PriceListItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PriceListItemRepository extends JpaRepository<PriceListItem, Long> {
    List<PriceListItem> findByPriceListIdOrderByLineNoAscIdAsc(Long priceListId);

    /** Строки действующих прайсов поставщика, кроме этого прайса (фильтр рынка — через прайс, аспектом). */
    @Query("SELECT i FROM PriceListItem i JOIN FETCH i.priceList p WHERE p.distributor.id = :distributorId "
            + "AND p.id <> :exceptId AND p.status = com.vladoose.nir.entity.PriceListStatus.ACTIVE")
    List<PriceListItem> findOtherActiveOfDistributor(@Param("distributorId") Long distributorId, @Param("exceptId") Long exceptId);
}
