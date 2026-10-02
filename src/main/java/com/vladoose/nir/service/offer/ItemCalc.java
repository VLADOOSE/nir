package com.vladoose.nir.service.offer;

import java.math.BigDecimal;

/**
 * Расчёт строки КП за единицу и на количество (спека §5.1). cost — себестоимость за ед. (без входного НДС, если
 * строка с НДС), costTotal — на количество; price — цена клиенту за ед. (с НДС, если строка с НДС); суммы — на
 * количество; effectiveVatRate — ставка с учётом общего переключателя НДС (null — без НДС). У SECTION/INCLUDED — NONE.
 */
public record ItemCalc(BigDecimal cost, BigDecimal costTotal, BigDecimal markupPct, BigDecimal priceNet, BigDecimal price,
                       BigDecimal sum, BigDecimal vatSum, BigDecimal sumNet, BigDecimal profit, BigDecimal effectiveVatRate) {

    public static final ItemCalc NONE = new ItemCalc(null, null, null, null, null, null, null, null, null, null);
}
