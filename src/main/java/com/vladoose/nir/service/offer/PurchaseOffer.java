package com.vladoose.nir.service.offer;

import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.PriceRequestItem;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Строка ответа поставщика с ценой — кандидат в закупку строки КП (спека 2026-10-12 §8). currency / original / rate —
 * цена пришла в чужой валюте (V44): исходная цена и курс, по которому она в валюте рынка; переходят в строку КП.
 */
public record PurchaseOffer(long priceRequestItemId, long priceRequestId, long lotId, long distributorId,
                            String supplierName, BigDecimal price, String status, LocalDate respondedOn, boolean auto,
                            String currency, BigDecimal original, BigDecimal rate) {

    /** Без признака 💡 — цена сверена (тесты правила закупки). */
    public PurchaseOffer(long priceRequestItemId, long priceRequestId, long lotId, long distributorId,
                         String supplierName, BigDecimal price, String status, LocalDate respondedOn) {
        this(priceRequestItemId, priceRequestId, lotId, distributorId, supplierName, price, status, respondedOn, false, null, null, null);
    }

    public static PurchaseOffer of(PriceRequestItem i) {
        PriceRequest pr = i.getPriceRequest();
        return new PurchaseOffer(i.getId(), pr.getId(), i.getTenderLot().getId(), pr.getDistributor().getId(),
                pr.getDistributor().getName(), i.getResponsePrice(), pr.getStatus(), pr.getResponseDate(), i.isResponseAuto(),
                i.getResponseCurrency(), i.getResponseOriginal(), i.getResponseRate());
    }
}
