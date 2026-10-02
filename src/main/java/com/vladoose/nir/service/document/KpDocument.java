package com.vladoose.nir.service.document;

import com.vladoose.nir.service.offer.ColumnAlign;

import java.util.List;

/**
 * Неизменяемая модель документа КП (спека §6.4): всё уже посчитано и отформатировано — рендереры PDF и Word
 * ничего не считают и не форматируют сами, поэтому документы не расходятся по содержанию.
 */
public record KpDocument(
        boolean landscape,
        Letterhead letterhead,
        String numberLine,
        List<String> recipientLines,
        String title,
        String subject,
        String intro,
        List<Term> termsTable,
        List<Column> columns,
        List<Row> rows,
        List<String> totalLines,
        String amountInWords,
        List<String> termsList,
        Signoff signoff) {

    public record Letterhead(List<String> left, List<String> right, byte[] logoPng, String brandText, List<String> lines) {}

    public record Term(String label, List<String> valueLines) {}

    /** percent — доля ширины таблицы, сумма по колонкам = 100. */
    public record Column(String key, String label, ColumnAlign align, int percent) {}

    public enum RowKind { ITEM, SECTION, INCLUDED }

    /** cells — ячейки колонок [0, spanFrom); если spanFrom < колонок — затем одна объединённая ячейка spanLines. */
    public record Row(RowKind kind, List<List<String>> cells, int spanFrom, List<String> spanLines) {}

    public record Signoff(boolean director, String titleLine, String nameLine, String contacts,
                          byte[] signaturePng, byte[] stampPng, int stampSizeMm) {}
}
