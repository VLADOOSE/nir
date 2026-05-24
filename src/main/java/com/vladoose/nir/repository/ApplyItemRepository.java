package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ApplyItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ApplyItemRepository extends JpaRepository<ApplyItem, Long> {

    List<ApplyItem> findByApplyId(Long applyId);

    List<ApplyItem> findByMedEquipmentIdIn(Collection<Long> ids);

    boolean existsByMedEquipmentId(Long medEquipmentId);

    boolean existsByTenderLotId(Long tenderLotId);

    @Query("SELECT ai.distributor.name, COUNT(ai), AVG(ai.offeredCost) FROM ApplyItem ai WHERE ai.distributor IS NOT NULL GROUP BY ai.distributor.name")
    List<Object[]> distributorStats();
}
