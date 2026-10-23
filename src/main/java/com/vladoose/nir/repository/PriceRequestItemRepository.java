package com.vladoose.nir.repository;

import com.vladoose.nir.entity.PriceRequestItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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

    /** Когда этот поставщик последний раз получал запрос по лоту — письма из ответа ИИ (спека 2026-10-21 §6). */
    @Query("SELECT max(i.priceRequest.sentAt) FROM PriceRequestItem i "
            + "WHERE i.tenderLot.id = :lotId AND i.priceRequest.distributor.id = :distId")
    OffsetDateTime lastRequestedAt(@Param("lotId") Long lotId, @Param("distId") Long distId);

    /**
     * Ответы с ценой для истории цен (спека 2026-10-23 §7), рынок явно: [id строки, id запроса, поставщик, исходная цена,
     * валюта (пусто — валюта рынка), дата ответа, наименование позиции, бренд, позиция каталога, наш товар].
     */
    @Query(value = """
            SELECT i.id, pr.id, d.name, COALESCE(i.response_original, i.response_price), i.response_currency,
                   COALESCE(pr.response_date, CAST(pr.sent_at AS date), CAST(pr.created_at AS date)),
                   l.equip_name, l.manufact, e.id, e.site_product_id
            FROM price_request_item i
            JOIN price_request pr ON pr.id = i.price_request_id
            JOIN distributor d ON d.id = pr.distributor_id
            JOIN tender_lot l ON l.id = i.tender_lot_id
            LEFT JOIN med_equipment e ON e.id = COALESCE(i.med_equipment_id, l.proposed_equipment_id)
            WHERE pr.market = :market AND i.response_price IS NOT NULL
            ORDER BY i.id DESC LIMIT 2000
            """, nativeQuery = true)
    List<Object[]> findPricedForHistory(@Param("market") String market);
}
