package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.PriceListColumn;
import com.vladoose.nir.entity.ReplyParseStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * Итог разбора прайса (спека 2026-10-23 §5.2): статус и пометка, валюта (MIXED — не ясна; null — не названа), колонки
 * цены, строки, колонка «наша закупка» по умолчанию, дата и срок прайса, «розничный», условия текстом, обрезано ли по
 * пределу строк.
 */
public record ParsedPriceList(ReplyParseStatus status, String note, String currency, List<PriceListColumn> columns,
                              List<ParsedPriceRow> rows, Integer ourColumn, LocalDate listDate, LocalDate validTo,
                              boolean retail, String terms, boolean truncated) {

    /** Не разобрался: без строк и колонок, с пометкой. */
    public static ParsedPriceList failed(ReplyParseStatus status, String note) {
        return new ParsedPriceList(status, note, null, List.of(), List.of(), null, null, null, false, null, false);
    }
}
