package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.Market;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ActivityApplyRepository extends JpaRepository<ActivityApply, Long> {

    List<ActivityApply> findByTenderId(Long tenderId);

    boolean existsByTenderId(Long tenderId);

    List<ActivityApply> findByStatus(String status);

    boolean existsByStatus(String status);

    // Воронка: тендеры с заявкой, у которой есть ≥1 позиция (WINNER_SELECTED).
    @Query("SELECT DISTINCT aa.tender.id FROM ActivityApply aa JOIN aa.items ai WHERE aa.market = :market")
    List<Long> findTenderIdsWithApplyItems(@Param("market") Market market);
}
