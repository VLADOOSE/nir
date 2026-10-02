package com.vladoose.nir.service.offer;

import java.math.BigDecimal;
import java.util.List;

/**
 * Итоги КП (§5.3) и маржа (§5.4, в документ не попадает). markupAvg — прибыль / себестоимость по строкам, где
 * себестоимость известна; null, если таких строк нет.
 */
public record OfferTotals(BigDecimal sum, List<VatLine> vat, BigDecimal vatTotal, BigDecimal purchase, BigDecimal cost,
                          BigDecimal revenueNet, BigDecimal profit, BigDecimal markupAvg,
                          int itemCount, int noPurchaseCount, int unconfirmedRegistrationCount) {}
