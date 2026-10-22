package com.vladoose.nir.integration.goszakup.dto;

/** Попытка закупки по пункту плана (спека §6): лот, статус, объявление, способ, срок приёма, поставщик закупки из одного источника. */
public record AttemptLot(Long lotId, String lotNumber, Integer statusId, String statusName, Long planPointId, String anno, Long buyId,
                         Integer tradeMethodId, String tradeMethodName, String publishDate, String endDate, String resultsAt,
                         String supplierBin) {}
