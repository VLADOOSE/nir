package com.vladoose.nir.service.pricelist;

import java.math.BigDecimal;
import java.util.List;

/**
 * Строка разобранного прайса (спека 2026-10-23 §5.2): fileRow — строка файла (для экрана проверки), section — раздел
 * над строкой, prices — цены по колонкам цены (null — ячейка пустая), regText — текст колонки «РУ» как в файле.
 */
public record ParsedPriceRow(int fileRow, String section, String name, String article, List<BigDecimal> prices,
                             String regText) {}
