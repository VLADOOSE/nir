package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Состоявшийся лот для цен рынка (спека §9): категория, бюджет, заказчик, место поставки, способ закупки. */
public record ClosedLot(Long id, String lotNumber, String nameRu, String descriptionRu, BigDecimal count, BigDecimal amount, Long buyId,
                        String anno, String customerBin, String customerName, String enstruCode, String extraDesc, String unit,
                        String deliveryPlace, String tradeMethodName) {}
