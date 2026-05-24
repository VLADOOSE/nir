package com.vladoose.nir.repository;

import com.vladoose.nir.entity.PriceRequestItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PriceRequestItemRepository extends JpaRepository<PriceRequestItem, Long> {

    List<PriceRequestItem> findByMedEquipmentId(Long medEquipmentId);

    List<PriceRequestItem> findByPriceRequestId(Long priceRequestId);

    @Query("SELECT i FROM PriceRequestItem i WHERE i.priceRequest.tender.id = :tenderId")
    List<PriceRequestItem> findByTenderId(@Param("tenderId") Long tenderId);

    @Query("SELECT i FROM PriceRequestItem i WHERE i.tenderLot.id = :lotId")
    List<PriceRequestItem> findByTenderLotId(@Param("lotId") Long lotId);
}
