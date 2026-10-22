package com.vladoose.nir.dto.response;

import java.math.BigDecimal;

/** Подсказка цены по тексту (окно «Запросить цены», строка КП, товар сайта): line пусто — договоров нет. */
public record MarketHint(String text, String line, int count, BigDecimal median, String code) {}
