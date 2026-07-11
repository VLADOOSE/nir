package com.vladoose.nir.integration.skpharmacy;

import java.math.BigDecimal;

/** Лот объявления СК-Фармации (вкладка lots). */
public record SkLot(String code, String name, BigDecimal unitPrice, Integer quantity) {}
