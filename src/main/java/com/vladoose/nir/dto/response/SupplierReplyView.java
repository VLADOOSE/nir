package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.LineField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Экран «Ответ поставщика» (спека 2026-10-15 §4.1). fileId / table — выбранный файл с разобранной таблицей (или null). */
public record SupplierReplyView(Head head, List<Position> positions, List<Source> sources, Long fileId, Table table, Counts counts) {

    public record Head(Long id, String status, String supplierName, Long tenderId, String tenderNumber, boolean privateRequest,
                       OffsetDateTime sentAt, LocalDate responseDate, String currency) {}

    /** row — строка файла, из которой цена (у выбранного файла); suggestion — подсказка для позиции без цены. */
    public record Position(Long itemId, int ordinal, String name, String brand, String model, Integer quantity, String unit,
                           BigDecimal price, boolean auto, String note, Long fileId, Integer fileRow, RowRef row,
                           RowRef suggestion, List<String> flags) {}

    /** currency — валюта строки файла (код): экран сравнивает с валютой рынка и чужую цену не выдаёт за свою. */
    public record RowRef(int fileRow, String text, BigDecimal quantity, String unit, BigDecimal price, boolean priceComputed,
                         String note, double score, String currency) {}

    public record Source(String type, Long fileId, Long inboundId, String name, String kind, String parseStatus,
                         String parseNote, Long sizeBytes, Integer pageCount, boolean uploaded, OffsetDateTime at,
                         String from, String subject, String text) {}

    public record Table(String source, int headerRow, String currency, List<Col> columns, List<TRow> rows) {}

    public record Col(int index, String header, LineField role) {}

    /** ordinal — позиция, к которой привязана строка (или null); text, price и currency — для списка «Другая строка ▾». */
    public record TRow(int fileRow, List<String> cells, Integer ordinal, boolean priced, String text, BigDecimal price,
                       String currency) {}

    public record Counts(int unverified, int noPrice, int unassignedRows) {}
}
