package com.vladoose.nir.repository;

import com.vladoose.nir.entity.PriceList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PriceListRepository extends JpaRepository<PriceList, Long> {

    Optional<PriceList> findByDistributorIdAndSha256(Long distributorId, String sha256);

    /**
     * Прайсы рынка без байтов файла (фильтр рынка — аспектом): [id, distributorId, distributorName, status, listDate,
     * validTo, currency, fileName, kind, parseStatus, createdBy, createdAt, itemCount], новые сверху.
     */
    @Query("SELECT p.id, d.id, d.name, p.status, p.listDate, p.validTo, p.currency, p.fileName, p.kind, p.parseStatus, "
            + "p.createdBy, p.createdAt, (SELECT count(i) FROM PriceListItem i WHERE i.priceList = p) "
            + "FROM PriceList p JOIN p.distributor d ORDER BY p.createdAt DESC, p.id DESC")
    List<Object[]> findRows();

    /** Сколько прайсов у поставщиков рынка: [distributorId, count]. */
    @Query("SELECT p.distributor.id, count(p) FROM PriceList p GROUP BY p.distributor.id")
    List<Object[]> countByDistributor();
}
