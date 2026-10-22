package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Строка договора по лоту: цена за единицу (с НДС, нет — без НДС), количество, сумма с НДС. */
public record ContractUnitRow(Long lotId, BigDecimal unitPrice, BigDecimal quantity, BigDecimal totalWithVat) {}
