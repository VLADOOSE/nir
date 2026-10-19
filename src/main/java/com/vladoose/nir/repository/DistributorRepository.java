package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Distributor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DistributorRepository extends JpaRepository<Distributor, Long> {

    @Query("SELECT DISTINCT d FROM Distributor d LEFT JOIN d.equipmentTypes et " +
           "WHERE et.id = :typeId OR SIZE(d.equipmentTypes) = 0")
    List<Distributor> findEligibleForType(@Param("typeId") Long typeId);

    /**
     * Все рынки: нативный запрос фильтр рынка не режет. Название поставщика уникально глобально (distributor_name_key),
     * поэтому «новый поставщик» сверяется со всеми рынками.
     */
    @Query(value = "SELECT id, market FROM distributor WHERE lower(name) = lower(:name)", nativeQuery = true)
    List<Object[]> findIdAndMarketByName(@Param("name") String name);

    /** id и название — подписи поставщиков исполнения (рынок режет аспект). */
    @Query("select d.id, d.name from Distributor d where d.id in :ids")
    List<Object[]> findNames(@Param("ids") Collection<Long> ids);
}
