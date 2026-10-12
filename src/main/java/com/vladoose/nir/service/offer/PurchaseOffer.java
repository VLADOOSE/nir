package com.vladoose.nir.service.offer;

import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.PriceRequestItem;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Строка ответа поставщика с ценой — кандидат в закупку строки КП (спека 2026-10-12 §8). */
public record PurchaseOffer(long priceRequestItemId, long priceRequestId, long lotId, long distributorId,
                            String supplierName, BigDecimal price, String status, LocalDate respondedOn) {

    public static PurchaseOffer of(PriceRequestItem i) {
        PriceRequest pr = i.getPriceRequest();
        return new PurchaseOffer(i.getId(), pr.getId(), i.getTenderLot().getId(), pr.getDistributor().getId(),
                pr.getDistributor().getName(), i.getResponsePrice(), pr.getStatus(), pr.getResponseDate());
    }
}
