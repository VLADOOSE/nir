package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.PriceListItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PriceListItemRepository extends JpaRepository<PriceListItem, Long> {
    List<PriceListItem> findByPriceListIdOrderByLineNoAscIdAsc(Long priceListId);

    /** Строки действующих прайсов поставщика, кроме этого прайса (фильтр рынка — через прайс, аспектом). */
    @Query("SELECT i FROM PriceListItem i JOIN FETCH i.priceList p WHERE p.distributor.id = :distributorId "
            + "AND p.id <> :exceptId AND p.status = com.vladoose.nir.entity.PriceListStatus.ACTIVE")
    List<PriceListItem> findOtherActiveOfDistributor(@Param("distributorId") Long distributorId, @Param("exceptId") Long exceptId);

    /**
     * Строки действующих прайсов рынка по ключам товара (подсказки, история): связь с нашим товаром, с позицией каталога
     * или код модели. «Валюта не ясна» и строки без закупки — мимо. Пустые списки — заглушка (-1 / "").
     */
    @Query("SELECT i FROM PriceListItem i JOIN FETCH i.priceList p JOIN FETCH p.distributor d "
            + "WHERE p.market = :market AND p.status = com.vladoose.nir.entity.PriceListStatus.ACTIVE "
            + "AND (p.currency IS NULL OR p.currency <> 'MIXED') AND i.ourPrice IS NOT NULL "
            + "AND (i.siteProductId IN :products OR i.medEquipmentId IN :equipment OR i.modelKey IN :keys)")
    List<PriceListItem> findActiveByKeys(@Param("market") Market market, @Param("products") Collection<Long> products,
                                         @Param("equipment") Collection<Long> equipment, @Param("keys") Collection<String> keys);

    /** Поиск по действующим прайсам рынка: слово — в наименовании, модели, артикуле или названии поставщика (LIKE с \). */
    @Query("SELECT i FROM PriceListItem i JOIN FETCH i.priceList p JOIN FETCH p.distributor d "
            + "WHERE p.market = :market AND p.status = com.vladoose.nir.entity.PriceListStatus.ACTIVE "
            + "AND (p.currency IS NULL OR p.currency <> 'MIXED') "
            + "AND (LOWER(i.name) LIKE :pattern ESCAPE '\\' OR LOWER(i.model) LIKE :pattern ESCAPE '\\' "
            + "OR LOWER(i.article) LIKE :pattern ESCAPE '\\' OR LOWER(d.name) LIKE :pattern ESCAPE '\\') ORDER BY i.id DESC")
    List<PriceListItem> findActiveByWord(@Param("market") Market market, @Param("pattern") String pattern, Pageable page);

    /** Подпись строки прайса у строки КП: [itemId, priceListId, поставщик, дата прайса, загружен]. */
    @Query("SELECT i.id, p.id, d.name, p.listDate, p.createdAt FROM PriceListItem i JOIN i.priceList p JOIN p.distributor d "
            + "WHERE i.id IN :ids")
    List<Object[]> findLabels(@Param("ids") Collection<Long> ids);
}
