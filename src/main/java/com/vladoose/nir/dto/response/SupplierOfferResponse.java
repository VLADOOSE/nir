package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Пункт «Поставщик ▾» (спека 2026-10-12 §8; 2026-10-23 §6.3): ответ поставщика с ценой или строка прайса (priceListItemId);
 * price — закупка в валюте рынка (у строки прайса — по нашему курсу; нет курса — пусто, выбрать нельзя); currency /
 * original / rate — исходная цена в чужой валюте.
 */
public record SupplierOfferResponse(Long priceRequestItemId, String supplierName, BigDecimal price, LocalDate respondedOn,
                                    boolean accepted, boolean cheapest, boolean selected, Long priceRequestId, boolean auto,
                                    Long priceListItemId, Long priceListId, String currency, BigDecimal original,
                                    BigDecimal rate, LocalDate listDate, boolean expired) {

    public SupplierOfferResponse(Long priceRequestItemId, String supplierName, BigDecimal price, LocalDate respondedOn,
                                 boolean accepted, boolean cheapest, boolean selected, Long priceRequestId, boolean auto) {
        this(priceRequestItemId, supplierName, price, respondedOn, accepted, cheapest, selected, priceRequestId, auto,
                null, null, null, null, null, null, false);
    }

    public SupplierOfferResponse(Long priceRequestItemId, String supplierName, BigDecimal price, LocalDate respondedOn,
                                 boolean accepted, boolean cheapest, boolean selected) {
        this(priceRequestItemId, supplierName, price, respondedOn, accepted, cheapest, selected, null, false);
    }

    public static SupplierOfferResponse fromPriceList(PriceHintRow r, boolean selected) {
        boolean foreign = r.ourRate() != null;
        return new SupplierOfferResponse(null, r.supplierName(), r.converted(), r.listDate(), false, false, selected, null, false,
                r.rowId(), r.priceListId(), r.currency(), foreign ? r.price() : null, r.ourRate(), r.listDate(), r.expired());
    }

    public SupplierOfferResponse withCheapest(boolean c) {
        return new SupplierOfferResponse(priceRequestItemId, supplierName, price, respondedOn, accepted, c, selected,
                priceRequestId, auto, priceListItemId, priceListId, currency, original, rate, listDate, expired);
    }
}
