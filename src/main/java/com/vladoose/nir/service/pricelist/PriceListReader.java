package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.PriceListColumn;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.supplierreply.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Разбор прайса (спека 2026-10-23 §5.2): Excel — таблица; Word — таблица, иначе строки текста; PDF — сетка (задача 6),
 * иначе строки текста. Строки — до последней строки с ценой (ниже — условия и срок), разделы — строки без цены с одной
 * надписью; шапка — из текста вне строк. Сбой — FAILED с пометкой, загрузка не падает.
 */
@Component
public class PriceListReader {

    public static final int PARSE_MAX = 15 * 1024 * 1024;
    static final long PARTS_MAX = 15L * 1024 * 1024;
    static final int ROWS_MAX = 2000;
    public static final String NO_ROWS = "Строки не найдены — добавьте вручную или попросите у поставщика Excel";

    private final HeaderDictionary dictionary;

    public PriceListReader(HeaderDictionary dictionary) {
        this.dictionary = dictionary;
    }

    public ParsedPriceList read(String fileName, ReplyFileKind kind, byte[] bytes) {
        return read(fileName, kind, bytes, dictionary.learned());
    }

    public static ParsedPriceList read(String fileName, ReplyFileKind kind, byte[] bytes, Map<String, LineField> learned) {
        if (bytes == null || bytes.length == 0) return ParsedPriceList.failed(ReplyParseStatus.FAILED, "Файл пустой");
        if (bytes.length > PARSE_MAX) return ParsedPriceList.failed(ReplyParseStatus.NOT_PARSED, "Файл больше 15 МБ — строки впишите вручную");
        try {
            switch (kind) {
                case EXCEL, WORD -> {
                    byte[] parse = OoxmlSlim.forParsing(bytes, PARTS_MAX);
                    if (parse == null) return ParsedPriceList.failed(ReplyParseStatus.NOT_PARSED, "Файл слишком большой для разбора — строки впишите вручную");
                    List<String> all = ReplyTableReader.allLines(parse, kind);
                    Optional<ReplyTable> t = ReplyTableReader.readAll(parse, kind, learned, true).stream()
                            .map(ReplyTableReader.ReadTable::table).max(Comparator.comparingLong(PriceListReader::pricedRows));
                    if (t.isPresent() && pricedRows(t.get()) > 0) return fromTable(t.get(), all, true, fileName);
                    return fromText(PriceTextLines.read(all), fileName);
                }
                case PDF -> {
                    return fromText(PriceTextLines.read(SupplierReplyFiles.pdfLines(bytes)), fileName);   // сетка — задача 6
                }
                default -> {
                    return ParsedPriceList.failed(ReplyParseStatus.NOT_PARSED, "Прайс картинкой не разбирается — строки впишите вручную");
                }
            }
        } catch (Exception | Error e) {   // как SupplierReplyFiles: и OutOfMemoryError внутри JVM — пометка, не падение загрузки
            return ParsedPriceList.failed(ReplyParseStatus.FAILED,
                    kind == ReplyFileKind.PDF ? "PDF не открылся — строки впишите вручную" : "Файл не открылся: защищён паролем или повреждён");
        }
    }

    /** Таблица → строки прайса; excludeData — убрать из текста шапки строки данных (у Excel / Word текст — весь документ). */
    public static ParsedPriceList fromTable(ReplyTable t, List<String> text, boolean excludeData, String fileName) {
        List<Integer> priceCols = new ArrayList<>(), nameCols = new ArrayList<>();
        Integer article = null, brand = null, reg = null;
        for (ReplyTable.Column c : t.columns()) {
            switch (c.role()) {
                case PRICE -> priceCols.add(c.index());
                case NAME -> nameCols.add(c.index());
                case ARTICLE -> { if (article == null) article = c.index(); }
                case MANUFACT -> { if (brand == null) brand = c.index(); }
                case REG -> { if (reg == null) reg = c.index(); }
                default -> { }
            }
        }
        List<PriceListColumn> columns = new ArrayList<>();
        for (int i = 0; i < priceCols.size(); i++) columns.add(new PriceListColumn(i, t.columns().get(priceCols.get(i)).header()));
        int lastPriced = -1;
        for (int i = 0; i < t.rows().size(); i++) if (priced(t.rows().get(i).cells(), priceCols)) lastPriced = i;
        Set<String> data = new HashSet<>();
        data.add(norm(String.join(" ", t.columns().stream().map(ReplyTable.Column::header).toList())));
        List<ParsedPriceRow> rows = new ArrayList<>();
        String section = null;
        boolean truncated = false;
        for (int i = 0; i <= lastPriced; i++) {
            ReplyTable.Row r = t.rows().get(i);
            List<String> cells = r.cells();
            data.add(norm(ReplyTableReader.joined(cells)));
            List<BigDecimal> prices = new ArrayList<>();
            for (int c : priceCols) prices.add(price(cell(cells, c)));
            boolean any = prices.stream().anyMatch(Objects::nonNull);
            String name = join(cells, nameCols);
            String b = brand == null ? "" : cell(cells, brand);
            if (!b.isBlank() && !name.toLowerCase(Locale.ROOT).contains(b.toLowerCase(Locale.ROOT))) name = (name + " " + b).strip();
            if (!any) {
                String s = sectionText(cells);
                if (s != null) {
                    section = PriceTextLines.cut(s, 255);
                    continue;
                }
            }
            if (name.isBlank()) continue;
            rows.add(new ParsedPriceRow(r.fileRow(), section, PriceTextLines.cut(name, 1000),
                    article == null ? null : blankToNull(PriceTextLines.cut(cell(cells, article), 100)), prices,
                    reg == null ? null : blankToNull(PriceTextLines.cut(cell(cells, reg), 500))));
            if (rows.size() >= ROWS_MAX) {
                truncated = true;
                break;
            }
        }
        List<String> rest = new ArrayList<>();
        for (String l : text) if (!excludeData || !data.contains(norm(l))) rest.add(l);
        PriceListHeader h = PriceListHeader.of(rest, fileName);
        String currency = t.currency() != null ? t.currency() : PriceListHeader.currency(rest);
        return new ParsedPriceList(ReplyParseStatus.PARSED, null, currency, columns, rows,
                PriceListHeader.defaultColumn(columns, rows), h.listDate(), h.validTo(), h.retail(), h.terms(), truncated);
    }

    static ParsedPriceList fromText(PriceTextLines.Result r, String fileName) {
        PriceListHeader h = PriceListHeader.of(r.text(), fileName);
        if (r.rows().isEmpty()) {
            return new ParsedPriceList(ReplyParseStatus.NO_TABLE, NO_ROWS, PriceListHeader.currency(r.text()), List.of(), List.of(),
                    null, h.listDate(), h.validTo(), h.retail(), h.terms(), false);
        }
        String currency = r.currency() != null ? r.currency() : PriceListHeader.currency(r.text());
        return new ParsedPriceList(ReplyParseStatus.PARSED, null, currency, List.of(new PriceListColumn(0, "Цена")), r.rows(), 0,
                h.listDate(), h.validTo(), h.retail(), h.terms(), r.rows().size() >= ROWS_MAX);
    }

    static long pricedRows(ReplyTable t) {
        List<Integer> priceCols = t.columns().stream().filter(c -> c.role() == LineField.PRICE).map(ReplyTable.Column::index).toList();
        return t.rows().stream().filter(r -> priced(r.cells(), priceCols)).count();
    }

    private static boolean priced(List<String> cells, List<Integer> priceCols) {
        for (int c : priceCols) if (price(cell(cells, c)) != null) return true;
        return false;
    }

    private static BigDecimal price(String cell) {
        BigDecimal v = MoneyText.amount(cell);
        return v == null || v.signum() <= 0 ? null : v;
    }

    /** Строка-раздел: одна надпись (одна непустая ячейка или все непустые одинаковы), не длиннее 120. */
    private static String sectionText(List<String> cells) {
        String only = null;
        for (String c : cells) {
            if (c == null || c.isBlank()) continue;
            String v = c.strip();
            if (only == null) only = v;
            else if (!only.equals(v)) return null;
        }
        return only == null || only.length() > 120 ? null : only;
    }

    private static String join(List<String> cells, List<Integer> cols) {
        StringBuilder b = new StringBuilder();
        for (int c : cols) {
            String v = cell(cells, c).strip();
            if (v.isEmpty() || b.toString().contains(v)) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(v);
        }
        return b.toString().replaceAll("\\s+", " ").strip();
    }

    private static String cell(List<String> cells, int c) {
        return c < cells.size() && cells.get(c) != null ? cells.get(c) : "";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String norm(String s) {
        return s == null ? "" : s.replace('\u00A0', ' ').replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
}
