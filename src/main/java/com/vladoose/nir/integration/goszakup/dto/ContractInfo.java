package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Договор объявления (итоги, спека §11.2). */
public record ContractInfo(String supplierBiin, BigDecimal contractSum, BigDecimal contractSumWnds, String signDate, Integer statusId) {}
