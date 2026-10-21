package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.LineField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Экран «Ответ поставщика» (спека 2026-10-15 §4.1). fileId / table — выбранный файл с разобранной таблицей (или null);
 * rate — поле курса, когда цены выбранного файла не в валюте запроса (иначе null).
 */
public record SupplierReplyView(Head head, List<Position> positions, List<Source> sources, Long fileId, Table table, Counts counts,
                                Rate rate) {

    public record Head(Long id, String status, String supplierName, Long tenderId, String tenderNumber, boolean privateRequest,
                       OffsetDateTime sentAt, LocalDate responseDate, String currency) {}

    /** row — строка файла, из которой цена (у выбранного файла); suggestion — подсказка для позиции без цены. */
    public record Position(Long itemId, int ordinal, String name, String brand, String model, Integer quantity, String unit,
                           BigDecimal price, boolean auto, String note, Long fileId, Integer fileRow, RowRef row,
                           RowRef suggestion, List<String> flags) {}

    /**
     * currency — валюта строки файла (код): экран сравнивает с валютой рынка и чужую цену не выдаёт за свою; converted —
     * цена, пересчитанная по курсу файла в валюту запроса (курса нет или валюта не та — null).
     */
    public record RowRef(int fileRow, String text, BigDecimal quantity, String unit, BigDecimal price, boolean priceComputed,
                         String note, double score, String currency, BigDecimal converted) {}

    public record Source(String type, Long fileId, Long inboundId, String name, String kind, String parseStatus,
                         String parseNote, Long sizeBytes, Integer pageCount, boolean uploaded, OffsetDateTime at,
                         String from, String subject, String text) {}

    /**
     * currency — валюта цен файла (код; MIXED — не ясна; null — не названа, считается валютой запроса); currencyOrigin —
     * откуда она: строка над таблицей файла, если не из самой таблицы; totals — итоговые строки файла (под таблицей);
     * discount — итог «со скидкой» меньше суммы строк: скидка только в итоге, цены строк — без неё (иначе null).
     */
    public record Table(String source, int headerRow, String currency, CurrencyOrigin currencyOrigin, List<Col> columns,
                        List<TRow> rows, List<Total> totals, Discount discount) {}

    public record Total(String label, BigDecimal amount) {}

    /** label и amount — итоговая строка файла со скидкой; rowsSum — сумма строк таблицы (без скидки). */
    public record Discount(String label, BigDecimal amount, BigDecimal rowsSum) {}

    /**
     * Откуда валюта цен файла: source — TABLE (шапка, колонка «Валюта», цены), TEXT (текст документа вне таблицы;
     * texts — где нашлась), MIXED (разные валюты или суммы в тысячах; texts — где), NONE (не названа — валюта запроса),
     * MANUAL (выбрал человек: by — кто, at — когда).
     */
    public record CurrencyOrigin(String source, List<String> texts, String by, OffsetDateTime at) {}

    public record Col(int index, String header, LineField role) {}

    /** ordinal — позиция, к которой привязана строка (или null); text, price, currency, converted — для «Другая строка ▾». */
    public record TRow(int fileRow, List<String> cells, Integer ordinal, boolean priced, String text, BigDecimal price,
                       String currency, BigDecimal converted) {}

    public record Counts(int unverified, int noPrice, int unassignedRows) {}

    /**
     * Курс выбранного файла: 1 currency = value валюты запроса; suggestion — последний курс этой валюты на рынке,
     * suggestedAt — когда его вписали (курс меняется — оператор видит, насколько подсказка старая).
     */
    public record Rate(String currency, BigDecimal value, BigDecimal suggestion, OffsetDateTime suggestedAt) {}
}
