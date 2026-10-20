package com.vladoose.nir.dto.response;

import java.math.BigDecimal;

/** «Итоги ›» объявления: договор (поставщик, сумма с НДС, дата подписи) или «договор ещё не опубликован». */
public record AnnouncementResult(String status, String supplierName, String supplierBin, BigDecimal contractSum, String signDate) {}
