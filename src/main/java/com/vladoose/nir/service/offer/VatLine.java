package com.vladoose.nir.service.offer;

import java.math.BigDecimal;

/** «в т.ч. НДС 5% — сумма». */
public record VatLine(BigDecimal rate, BigDecimal amount) {}
