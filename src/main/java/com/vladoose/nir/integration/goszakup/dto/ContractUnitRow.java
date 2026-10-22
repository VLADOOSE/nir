package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/**
 * Строка договора по лоту: цена за единицу (с НДС, нет — без НДС), количество, сумма с НДС и код ЕНС ТРУ — у лотов 2026 года
 * площадка не отдаёт пункт плана («Plans» пусты), и код есть только здесь (живая проверка 2026-10-22).
 */
public record ContractUnitRow(Long lotId, BigDecimal unitPrice, BigDecimal quantity, BigDecimal totalWithVat, String enstruCode) {

    public ContractUnitRow(Long lotId, BigDecimal unitPrice, BigDecimal quantity, BigDecimal totalWithVat) {
        this(lotId, unitPrice, quantity, totalWithVat, null);
    }
}
