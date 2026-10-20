package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Лот из v3-поиска с вложенным объявлением. */
public record FoundLot(Long id, String lotNumber, String nameRu, String descriptionRu, BigDecimal count, BigDecimal amount,
                       Integer refLotStatusId, String lastUpdateDate, String customerBin, String customerNameRu,
                       String trdBuyNumberAnno, FoundAnnouncement announcement) {}
