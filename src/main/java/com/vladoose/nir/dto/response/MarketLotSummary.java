package com.vladoose.nir.dto.response;

import java.math.BigDecimal;

/** Строка «Рынок: …» у лота (только из набора, без запросов к площадке — решение 10). */
public record MarketLotSummary(String text, int count, BigDecimal median, String code) {}
