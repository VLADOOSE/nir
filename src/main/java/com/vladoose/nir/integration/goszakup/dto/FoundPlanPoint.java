package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Пункт плана закупок из v3 (спека §10). */
public record FoundPlanPoint(Long id, Integer year, Integer month, String monthName, String subjectBiin, String subjectNameRu,
                             String nameRu, String descRu, String extraDescRu, BigDecimal count, String unit, BigDecimal price,
                             BigDecimal amount, String tradeMethod, Integer statusId, String statusName, String deliveryPlace) {}
