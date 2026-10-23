package com.vladoose.nir.service.offer;

import java.math.BigDecimal;
import java.util.List;

/**
 * Итоги КП (§5.3) и маржа (§5.4, в документ не попадает). purchase — Σ закупок строк (каждая до 0,01); cost — то же
 * число: НДС поставщика не вычитается (решение оператора 2026-10-05), поле оставлено для совместимости API. vatTotal —
 * наш НДС с продажи. markupAvg — от закупки, по суммам с НДС: (Σ sum − Σ costTotal) / Σ costTotal × 100 по строкам
 * с закупкой больше нуля (одна наценка 20 % у всех строк — 20 %); null, если таких строк нет. purchaseToday /
 * profitToday — закупка и прибыль по курсу сегодня (спека 2026-10-23 §4.5): строки в чужой валюте — исходная цена × курс
 * сегодня, остальные — их закупка; null — строк в чужой валюте нет или у их валюты нет курса сегодня.
 */
public record OfferTotals(BigDecimal sum, List<VatLine> vat, BigDecimal vatTotal, BigDecimal purchase, BigDecimal cost,
                          BigDecimal revenueNet, BigDecimal profit, BigDecimal markupAvg,
                          int itemCount, int noPurchaseCount, int unconfirmedRegistrationCount,
                          BigDecimal purchaseToday, BigDecimal profitToday) {}
