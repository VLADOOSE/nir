package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Объявление из v3-поиска (спека 2026-10-19-product-watch §7.2). */
public record FoundAnnouncement(Long id, String numberAnno, String nameRu, String orgBin, String orgNameRu, BigDecimal totalSum,
                                String publishDate, String endDate, Integer refBuyStatusId, Integer refTradeMethodsId,
                                String tradeMethodName, Integer systemId) {

    /** Для прежнего писателя импорта ({@link com.vladoose.nir.integration.goszakup.GoszakupTenderWriter}). */
    public TrdBuyDto toTrdBuy() {
        TrdBuyDto d = new TrdBuyDto();
        d.setId(id);
        d.setNumberAnno(numberAnno);
        d.setNameRu(nameRu);
        d.setOrgBin(orgBin);
        d.setTotalSum(totalSum);
        d.setPublishDate(publishDate);
        d.setEndDate(endDate);
        d.setRefBuyStatusId(refBuyStatusId);
        d.setSystemId(systemId);
        return d;
    }
}
