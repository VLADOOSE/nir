package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.PriceListColumn;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Экран проверки прайса (спека §5.3): шапка, колонки цен, курсы (наш и сегодня — для «→ тг»), строки с закупкой, РУ
 * (ACTIVE / EXPIRED / NOT_FOUND — по реестру, NONE — «нет РУ» по тексту, null — номера нет), связью и «было …».
 */
public record PriceListView(Long id, String status, Long distributorId, String distributorName, String fileName, String kind,
                            String parseStatus, String parseNote, LocalDate listDate, LocalDate validTo, boolean expired,
                            String currency, boolean retail, List<PriceListColumn> columns, Integer ourColumn, BigDecimal discountPct,
                            String terms, String marketCurrency, BigDecimal ourRate, BigDecimal todayRate, LocalDate todayDate,
                            String createdBy, OffsetDateTime createdAt, String activatedBy, OffsetDateTime activatedAt, List<Row> rows) {

    public record Row(Long id, int lineNo, String section, String name, String model, String article, List<BigDecimal> prices,
                      BigDecimal ourPrice, BigDecimal converted, BigDecimal today, String regText, String regNumber,
                      String regStatus, LocalDate regExpires, boolean noReg, Long siteProductId, String siteProductName,
                      String siteProductUrl, Long medEquipmentId, String medEquipmentName, BigDecimal prevPrice,
                      LocalDate prevDate, BigDecimal prevPct, Integer fileRow) {}
}
