package com.vladoose.nir.repository;

import com.vladoose.nir.entity.PriceRequestItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PriceRequestItemRepository extends JpaRepository<PriceRequestItem, Long> {

    List<PriceRequestItem> findByMedEquipmentId(Long medEquipmentId);

    boolean existsByTenderLotId(Long tenderLotId);

    List<PriceRequestItem> findByPriceRequestId(Long priceRequestId);

    @Query("SELECT i FROM PriceRequestItem i WHERE i.priceRequest.tender.id = :tenderId")
    List<PriceRequestItem> findByTenderId(@Param("tenderId") Long tenderId);

    /** Строки ответов с ценой по заявке — с запросом, поставщиком и позицией (КП из заявки, спека 2026-10-12 §8). */
    @Query("SELECT i FROM PriceRequestItem i JOIN FETCH i.priceRequest pr JOIN FETCH pr.distributor JOIN FETCH i.tenderLot " +
           "WHERE pr.tender.id = :tenderId AND i.responsePrice IS NOT NULL")
    List<PriceRequestItem> findPricedByTenderId(@Param("tenderId") Long tenderId);

    @Query("SELECT i FROM PriceRequestItem i WHERE i.tenderLot.id = :lotId")
    List<PriceRequestItem> findByTenderLotId(@Param("lotId") Long lotId);

    /**
     * Поиск закупочной цены (responsePrice) на пару лот+оборудование от конкретного дистрибьютора.
     * Сортировка по дате создания PR убывающая — берётся самый свежий ответ.
     */
    @Query("SELECT i FROM PriceRequestItem i " +
           "WHERE i.tenderLot.id = :lotId AND i.medEquipment.id = :eqId " +
           "AND i.priceRequest.distributor.id = :distId AND i.responsePrice IS NOT NULL " +
           "ORDER BY i.priceRequest.createdAt DESC")
    List<PriceRequestItem> findResponseFor(@Param("lotId") Long lotId,
                                            @Param("eqId") Long eqId,
                                            @Param("distId") Long distId);

    /** Для этапа заявки: (заявка, позиция) с введённой ценой ответа. */
    @Query("SELECT DISTINCT i.priceRequest.tender.id, i.tenderLot.id FROM PriceRequestItem i "
         + "WHERE i.priceRequest.tender.id IN :ids AND i.responsePrice IS NOT NULL")
    List<Object[]> findPricedLots(@Param("ids") Collection<Long> ids);

    /** Строки ответа запроса — под блокировкой: ручная цена, сохранённая в ту же секунду, не затрётся подстановкой. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM PriceRequestItem i WHERE i.priceRequest.id = :prId ORDER BY i.id")
    List<PriceRequestItem> lockByPriceRequestId(@Param("prId") Long prId);

    /** «Всё верно» — снять 💡 со всех цен запроса. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PriceRequestItem i SET i.responseAuto = false WHERE i.priceRequest.id = :prId AND i.responseAuto = true")
    int clearAuto(@Param("prId") Long prId);
}
