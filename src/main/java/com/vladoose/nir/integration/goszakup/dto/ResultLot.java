package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Лот объявления для итогов: статус, количество и сумма, пункт плана, код ЕНС ТРУ и единица пункта плана. */
public record ResultLot(Long id, String lotNumber, Integer statusId, String statusName, BigDecimal count, BigDecimal amount,
                        Long planPointId, String enstruCode, String unit, String customerBin) {}
