package com.vladoose.nir.repository;

import com.vladoose.nir.entity.PriceListItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceListItemRepository extends JpaRepository<PriceListItem, Long> {
    List<PriceListItem> findByPriceListIdOrderByLineNoAscIdAsc(Long priceListId);
}
