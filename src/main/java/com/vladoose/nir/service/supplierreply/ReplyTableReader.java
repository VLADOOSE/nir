package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyCurrencySource;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyTable;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Таблица КП поставщика из Excel (.xlsx / .xls) и Word (.docx / .doc) — спека 2026-10-15 §3.2. Шапка — строка с
 * наибольшим числом узнанных заголовков (не меньше двух, среди них наименование и цена или сумма) в первых 40 строках
 * листа или первых 20 строках таблицы Word (форма КП для больниц РК: сверху в той же таблице — 8 строк условий); бланк и обращение над ней отбрасываются; шапка в две строки склеивается;
 * конец таблицы — «Итого» / «Всего» / «Итог»; таблица Word, разорванная по страницам (следующая таблица той же ширины
 * без своей шапки), склеивается. Валюта — из шапки и ячеек таблицы, а нет там — из текста вне её (шапка и всё над ней,
 * строка «Итого» и всё под ней, абзацы Word; валюта — вплотную к числу или явной фразой, MoneyText.textHits): ровно одна
 * валюта — она, разные или суммы в тысячах — «не ясна» (MoneyText.MIXED). Сбой чтения — исключение: его ловит
 * SupplierReplyFiles.
 */
public final class ReplyTableReader {

    static final int HEADER_SCAN = 40;
    static final int WORD_HEADER_SCAN = 20;
    static final int MAX_ROWS = 2000;
    static final int MAX_COLS = 40;
    private static final Pattern TOTAL = Pattern.compile("^(итого|всего|итог|total)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LINE_BREAK = Pattern.compile("[\\r\\n\\u000B\\f]+");
    /** Число в тексте: «171 343,00», «90 000», «4»; группы по три цифры через пробел — одно число. */
    private static final Pattern TEXT_NUMBER = Pattern.compile(
            "(?<![\\d.,])(?:\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})+|\\d+)(?:[.,]\\d{1,2})?(?!\\d)");
    /** Итоговых строк файла — не больше; подпись — до TOTAL_LABEL символов. */
    static final int MAX_TOTALS = 6;
    static final int TOTAL_LABEL = 160;
    /** Фрагмент текста, где нашлась валюта, — до 120 символов («по тексту «…»»); при разных валютах — по 80 на каждую. */
    static final int FRAGMENT = 120;
    static final int MIXED_FRAGMENT = 80;

    private ReplyTableReader() {}

    /**
     * Строки таблицы как текст; firstRow — номер первой строки в файле (с 1); word — таблица Word (шапка в первых 20
     * строках); text — текст документа вне таблиц (абзацы Word; у листа Excel — пусто: всё на листе и так в rows).
     */
    record Grid(String source, List<List<String>> rows, int firstRow, boolean word, List<String> text) {}

    /** Документ: сетки (таблицы Word, листы Excel) и абзацы вне таблиц (у Excel — пусто). */
    record Document(List<Grid> grids, List<String> text) {}

    /** Таблица файла и число её строки «Итого» (null — строки «Итого» нет или числа в ней нет). */
    public record ReadTable(ReplyTable table, BigDecimal total) {}

    /** Таблица из сетки и её итог — общая основа read и readAll. */
    record Parsed(ReplyTable table, BigDecimal total) {}

    /** Сколько первых строк каждой таблицы / листа идёт в текст документа (бланк бывает таблицей). */
    static final int TEXT_ROWS = 10;

    static Document document(byte[] content, ReplyFileKind kind) throws IOException {
        return switch (kind) {
            case EXCEL -> new Document(excel(content), List.of());
            case WORD -> ole2(content) ? doc(content) : docx(content);
            default -> new Document(List.of(), List.of());
        };
    }

    public static Optional<ReplyTable> read(byte[] content, ReplyFileKind kind, Map<String, LineField> learned) throws IOException {
        List<Grid> grids = document(content, kind).grids();
        List<Grid> candidates = new ArrayList<>(grids);
        if (kind == ReplyFileKind.WORD) {
            // таблица на нескольких страницах: следующая таблица той же ширины без своей шапки — её продолжение
            for (int i = 0; i < grids.size(); i++) {
                Grid acc = grids.get(i);
                for (int j = i + 1; j < grids.size() && width(grids.get(j)) == width(grids.get(i))
                        && !startsWithHeader(grids.get(j), learned); j++) {
                    acc = glue(acc, grids.get(j));
                    candidates.add(acc);
                }
            }
        }
        return candidates.stream().map(g -> table(g, learned)).flatMap(Optional::stream)
                .max(Comparator.comparingInt(ReplyTable::score).thenComparingInt(t -> t.rows().size()));
    }

    /**
     * Все таблицы документа с наименованием и ценой (готовое КП, спека 2026-10-19 §4.1) — в порядке документа. Таблица
     * Word той же ширины без своей шапки — продолжение предыдущей (как в read) и отдельной таблицей не считается.
     */
    public static List<ReadTable> readAll(byte[] content, ReplyFileKind kind, Map<String, LineField> learned) throws IOException {
        List<Grid> grids = document(content, kind).grids();
        List<ReadTable> out = new ArrayList<>();
        for (int i = 0; i < grids.size(); i++) {
            Grid acc = grids.get(i);
            int j = i + 1;
            if (kind == ReplyFileKind.WORD) {
                for (; j < grids.size() && width(grids.get(j)) == width(grids.get(i))
                        && !startsWithHeader(grids.get(j), learned); j++) {
                    acc = glue(acc, grids.get(j));
                }
            }
            Optional<Parsed> p = parse(acc, learned);
            if (p.isEmpty()) continue;
            out.add(new ReadTable(p.get().table(), p.get().total()));
            i = j - 1;   // продолжения — строки этой таблицы
        }
        return out;
    }

    /** Абзацы документа вне таблиц (Word; у Excel — пусто): «Итого: …» под таблицей готового КП. */
    public static List<String> paragraphs(byte[] content, ReplyFileKind kind) throws IOException {
        return document(content, kind).text();
    }

    /** Текст документа для номера и даты КП: абзацы Word, затем первые строки каждой таблицы / листа (бланк в таблице). */
    public static List<String> documentLines(byte[] content, ReplyFileKind kind) throws IOException {
        Document d = document(content, kind);
        List<String> out = new ArrayList<>(d.text());
        for (Grid g : d.grids()) {
            for (int r = 0; r < Math.min(g.rows().size(), TEXT_ROWS); r++) {
                String line = joined(g.rows().get(r));
                if (!line.isEmpty()) out.add(line);
            }
        }
        return out;
    }

    // ---- Excel ----------------------------------------------------------------------------------------------------

    static List<Grid> excel(byte[] content) throws IOException {
        List<Grid> out = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                if (wb.isSheetHidden(s) || wb.isSheetVeryHidden(s)) continue;
                Sheet sheet = wb.getSheetAt(s);
                int last = Math.min(sheet.getLastRowNum(), HEADER_SCAN + MAX_ROWS + 2);
                List<List<String>> rows = new ArrayList<>();
                for (int r = 0; r <= last; r++) {
                    Row row = sheet.getRow(r);
                    List<String> cells = new ArrayList<>();
                    if (row != null) {
                        int cols = Math.min(Math.max(row.getLastCellNum(), 0), MAX_COLS);
                        for (int c = 0; c < cols; c++) cells.add(text(row.getCell(c)));
                    }
                    rows.add(cells);
                }
                // объединённые ячейки шапки: текст — во все ячейки области («Цена, тг» над «за ед. | сумма»)
                for (CellRangeAddress m : sheet.getMergedRegions()) {
                    if (m.getFirstRow() > HEADER_SCAN + 1 || m.getFirstRow() >= rows.size()) continue;
                    String v = get(rows, m.getFirstRow(), m.getFirstColumn());
                    for (int r = m.getFirstRow(); r <= Math.min(m.getLastRow(), rows.size() - 1); r++) {
                        for (int c = m.getFirstColumn(); c <= Math.min(m.getLastColumn(), MAX_COLS - 1); c++) set(rows, r, c, v);
                    }
                }
                out.add(new Grid(sheet.getSheetName(), rows, 1, false, List.of()));
            }
        }
        return out;
    }

    static String text(Cell cell) {
        if (cell == null) return "";
        CellType t = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (t) {
            case STRING -> clean(cell.getStringCellValue());
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    // ---- Word -----------------------------------------------------------------------------------------------------

    static boolean ole2(byte[] b) {
        return b.length >= 4 && (b[0] & 0xff) == 0xD0 && (b[1] & 0xff) == 0xCF && (b[2] & 0xff) == 0x11 && (b[3] & 0xff) == 0xE0;
    }

    static Document docx(byte[] content) throws IOException {
        List<Grid> out = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<String> text = new ArrayList<>();
            for (XWPFParagraph p : doc.getParagraphs()) addLines(text, p.getText());
            int n = 0;
            for (XWPFTable t : doc.getTables()) {
                n++;
                List<List<String>> rows = new ArrayList<>();
                for (XWPFTableRow row : t.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String v = clean(cell.getText());
                        int span = span(cell);
                        for (int i = 0; i < span && cells.size() < MAX_COLS; i++) cells.add(v);   // объединённая по ширине — текст и в соседние
                    }
                    rows.add(cells);
                    if (rows.size() > WORD_HEADER_SCAN + MAX_ROWS) break;
                }
                out.add(new Grid("таблица " + n, rows, 1, true, text));
            }
            return new Document(out, text);
        }
    }

    /** Абзац — строками (мягкий перенос и разрыв строки внутри абзаца — новая строка): реквизиты и курс вырезаются построчно. */
    private static void addLines(List<String> out, String paragraph) {
        if (paragraph == null) return;
        for (String line : LINE_BREAK.split(paragraph)) {
            String v = clean(line);
            if (!v.isEmpty()) out.add(v);
        }
    }

    private static int span(XWPFTableCell cell) {
        CTTcPr pr = cell.getCTTc().getTcPr();
        if (pr == null || pr.getGridSpan() == null || pr.getGridSpan().getVal() == null) return 1;
        return Math.max(1, Math.min(MAX_COLS, pr.getGridSpan().getVal().intValue()));
    }

    static Document doc(byte[] content) throws IOException {
        List<Grid> out = new ArrayList<>();
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(content))) {
            Range range = doc.getRange();
            List<String> text = new ArrayList<>();
            for (int i = 0; i < range.numParagraphs(); i++) {
                Paragraph p = range.getParagraph(i);
                if (!p.isInTable()) addLines(text, Range.stripFields(p.text()));
            }
            TableIterator it = new TableIterator(range);
            int n = 0;
            while (it.hasNext()) {
                Table t = it.next();
                n++;
                List<List<String>> rows = new ArrayList<>();
                for (int r = 0; r < t.numRows() && rows.size() <= WORD_HEADER_SCAN + MAX_ROWS; r++) {
                    TableRow row = t.getRow(r);
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < row.numCells() && c < MAX_COLS; c++) cells.add(clean(row.getCell(c).text()));
                    rows.add(cells);
                }
                out.add(new Grid("таблица " + n, rows, 1, true, text));
            }
            return new Document(out, text);
        }
    }

    private static int width(Grid g) {
        return g.rows().stream().mapToInt(List::size).max().orElse(0);
    }

    private static Grid glue(Grid a, Grid b) {
        List<List<String>> rows = new ArrayList<>(a.rows());
        rows.addAll(b.rows());
        return new Grid(a.source(), rows, a.firstRow(), a.word(), a.text());
    }

    // ---- Таблица из сетки -----------------------------------------------------------------------------------------

    static Optional<ReplyTable> table(Grid g, Map<String, LineField> learned) {
        return parse(g, learned).map(Parsed::table);
    }

    /** Число строки «Итого»: в колонке суммы, а нет его там — наибольшее число строки («Итого (в т. ч. НДС 5 %…) | 1 360 000»). */
    static BigDecimal total(List<String> cells, List<ReplyTable.Column> columns) {
        for (ReplyTable.Column c : columns) {
            if (c.role() != LineField.SUM || c.index() >= cells.size()) continue;
            BigDecimal v = MoneyText.amount(cells.get(c.index()));
            if (v != null && v.signum() > 0) return v;
        }
        BigDecimal max = null;
        for (String cell : cells) {
            BigDecimal v = MoneyText.amount(cell);
            if (v != null && v.signum() > 0 && (max == null || v.compareTo(max) > 0)) max = v;
        }
        return max;
    }

    static Optional<Parsed> parse(Grid g, Map<String, LineField> learned) {
        List<List<String>> rows = g.rows();
        int scan = Math.min(rows.size(), g.word() ? WORD_HEADER_SCAN : HEADER_SCAN);
        int best = -1, bestScore = 0;
        for (int r = 0; r < scan; r++) {
            int score = headerScore(roles(rows.get(r), learned));
            if (score > bestScore) {
                best = r;
                bestScore = score;
            }
        }
        if (best < 0) return Optional.empty();
        List<String> header = new ArrayList<>(rows.get(best));
        List<LineField> roles = roles(header, learned);
        int data = best + 1;
        if (data + 1 < rows.size() && subHeader(rows.get(best), rows.get(data))) {   // шапка в две строки
            List<String> sub = rows.get(data);
            int w = Math.max(header.size(), sub.size());
            List<String> combined = new ArrayList<>(w);
            List<LineField> combinedRoles = new ArrayList<>(w);
            for (int c = 0; c < w; c++) {
                String top = c < header.size() ? header.get(c) : "", low = c < sub.size() ? sub.get(c) : "";
                String text = top.equals(low) || low.isBlank() ? top : (top + " " + low).trim();
                LineField lr = low.isBlank() || top.equals(low) ? LineField.IGNORE : HeaderRoles.role(low, learned);
                combined.add(text);
                combinedRoles.add(lr != LineField.IGNORE ? lr : HeaderRoles.role(text, learned));
            }
            combinedRoles = costAsPrice(combined, combinedRoles, learned);
            if (headerScore(combinedRoles) >= bestScore) {
                header = combined;
                roles = combinedRoles;
                data = best + 2;
            }
        }
        int width = header.size();
        for (int r = data; r < rows.size(); r++) width = Math.max(width, rows.get(r).size());
        width = Math.min(width, MAX_COLS);

        List<ReplyTable.Row> out = new ArrayList<>();
        // строка «Итого» — конец данных: она и всё под ней — текст вне таблицы; оборвались на пределе строк — остаток
        // тоже строки данных (в наименованиях бывает «… 1 500 руб.»), в текст не идёт
        int end = rows.size();
        List<String> totalRow = null;
        for (int r = data; r < rows.size() && out.size() < MAX_ROWS; r++) {
            List<String> cells = pad(rows.get(r), width);
            String first = cells.stream().filter(s -> !s.isBlank()).findFirst().orElse("");
            if (first.isEmpty()) continue;
            if (TOTAL.matcher(first).find()) {
                end = r;
                totalRow = cells;
                break;
            }
            out.add(new ReplyTable.Row(g.firstRow() + r, cells));
        }
        List<ReplyTable.Column> columns = new ArrayList<>(width);
        for (int c = 0; c < width; c++) {
            String h = c < header.size() ? header.get(c) : "";
            LineField role = c < roles.size() ? roles.get(c) : LineField.IGNORE;
            if (h.isBlank() && role == LineField.IGNORE && textColumn(out, c)) role = LineField.NOTE;   // решение плана 11
            columns.add(new ReplyTable.Column(c, h, role));
        }
        String currency = currency(columns, out);
        ReplyTable.CurrencyFrom from = new ReplyTable.CurrencyFrom(ReplyCurrencySource.TABLE, List.of(), null, null);
        if (currency == null) {
            TextCurrency tc = textCurrency(rows, data, end, g.text());
            if (tc == null) {
                from = new ReplyTable.CurrencyFrom(ReplyCurrencySource.NONE, List.of(), null, null);
            } else {
                currency = tc.code();
                ReplyCurrencySource kind = MoneyText.MIXED.equals(currency) ? ReplyCurrencySource.MIXED : ReplyCurrencySource.TEXT;
                from = new ReplyTable.CurrencyFrom(kind, tc.fragments(), null, null);
            }
        }
        ReplyTable table = new ReplyTable(g.source(), g.firstRow() + best, bestScore, currency, columns, out, from,
                totals(rows, end, width, columns, g.text()));
        return Optional.of(new Parsed(table, totalRow == null ? null : total(totalRow, columns)));
    }

    private static List<LineField> roles(List<String> row, Map<String, LineField> learned) {
        List<LineField> out = new ArrayList<>(row.size());
        for (String h : row) out.add(HeaderRoles.role(h, learned));
        return costAsPrice(row, out, learned);
    }

    /**
     * Форма КП для больниц РК: «Стоимость (тенге)» рядом с «Сумма (тенге)» — цена за единицу, а не вторая сумма. Только
     * если колонки цены нет, рядом есть «Сумма», роль «Стоимости» — догадка правил, а не словарь оператора, и в её
     * заголовке нет признаков итога, НДС или другой величины («Стоимость без НДС», «Общая стоимость», «Стоимость
     * доставки» — суммы).
     */
    private static List<LineField> costAsPrice(List<String> header, List<LineField> roles, Map<String, LineField> learned) {
        if (roles.contains(LineField.PRICE)) return roles;
        int cost = -1;
        boolean sum = false;
        for (int c = 0; c < roles.size(); c++) {
            if (roles.get(c) != LineField.SUM) continue;
            String h = HeaderRoles.norm(HeaderRoles.key(header.get(c)));
            if (h.contains("сумм")) sum = true;
            else if (cost < 0 && h.contains("стоимост") && !notUnitCost(h) && HeaderRoles.learnedRole(header.get(c), learned) == null) cost = c;
        }
        if (cost < 0 || !sum) return roles;
        List<LineField> out = new ArrayList<>(roles);
        out.set(cost, LineField.PRICE);
        return out;
    }

    private static boolean notUnitCost(String h) {
        for (String w : List.of("ндс", "всего", "общ", "итог", "доставк")) if (h.contains(w)) return true;
        return false;
    }

    /** Узнанные заголовки; 0 — не шапка (нет наименования или нет ни цены, ни суммы). */
    static int headerScore(List<LineField> roles) {
        boolean name = roles.contains(LineField.NAME);
        boolean money = roles.contains(LineField.PRICE) || roles.contains(LineField.SUM);
        if (!name || !money) return 0;
        return (int) roles.stream().filter(r -> r != LineField.IGNORE).count();
    }

    /**
     * Вторая строка шапки: без цифр, и каждая её непустая ячейка стоит под объединённой ячейкой верхней строки (тот же
     * текст у соседа слева или справа), под пустой или повторяет верхнюю (объединение по вертикали). Иначе это первая
     * строка данных без цифр («Ширма | шт | нет в наличии») — её нельзя съесть шапкой.
     */
    private static boolean subHeader(List<String> top, List<String> low) {
        boolean any = false;
        for (int c = 0; c < low.size(); c++) {
            String v = low.get(c);
            if (v.isBlank()) continue;
            if (v.chars().anyMatch(Character::isDigit)) return false;
            String t = c < top.size() ? top.get(c) : "";
            boolean underMerged = t.isBlank() || t.equals(v)
                    || (c > 0 && c - 1 < top.size() && t.equals(top.get(c - 1)))
                    || (c + 1 < top.size() && t.equals(top.get(c + 1)));
            if (!underMerged) return false;
            any = true;
        }
        return any;
    }

    private static boolean startsWithHeader(Grid g, Map<String, LineField> learned) {
        return !g.rows().isEmpty() && headerScore(roles(g.rows().get(0), learned)) > 0;
    }

    private static boolean textColumn(List<ReplyTable.Row> rows, int c) {
        for (ReplyTable.Row r : rows) {
            String v = r.cells().get(c);
            if (!v.isBlank() && v.chars().anyMatch(Character::isLetter)) return true;
        }
        return false;
    }

    /** Валюта таблицы: заголовки цены и суммы → колонка «Валюта» → сами цены; не названа — null. */
    private static String currency(List<ReplyTable.Column> columns, List<ReplyTable.Row> rows) {
        for (ReplyTable.Column c : columns) {
            if (c.role() == LineField.PRICE || c.role() == LineField.SUM) {
                String cur = MoneyText.currency(c.header());
                if (cur != null) return cur;
            }
        }
        for (ReplyTable.Column c : columns) {
            if (c.role() != LineField.CURRENCY && c.role() != LineField.PRICE && c.role() != LineField.SUM) continue;
            for (ReplyTable.Row r : rows) {
                String cur = MoneyText.currency(r.cells().get(c.index()));
                if (cur != null) return cur;
            }
        }
        return null;
    }

    /** Валюта из текста: код (MIXED — разные валюты или суммы в тысячах) и фрагменты текста, где нашлась. */
    record TextCurrency(String code, List<String> fragments) {}

    /**
     * Валюта из текста вне данных таблицы (абзац под таблицей: «на сумму … руб.»): шапка и всё над ней, строка «Итого» и
     * всё под ней — построчно, ячейки строки через пробел; абзацы Word. Строки данных не в счёт — в наименованиях бывает
     * «… 1 500 руб.». Правила — MoneyText.textHits (вплотную к числу или явной фразой). Ровно одна валюта — она; разные
     * (или суммы в тысячах) — MoneyText.MIXED (не угадывать); ни одной — null.
     */
    static TextCurrency textCurrency(List<List<String>> rows, int data, int end, List<String> text) {
        List<String> lines = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            if (r >= data && r < end) continue;
            String line = joined(rows.get(r));
            if (!line.isEmpty()) lines.add(line);
        }
        lines.addAll(text);
        Map<String, String> first = new LinkedHashMap<>();   // валюта → строка и находка, где она впервые
        Map<String, MoneyText.TextHit> hits = new LinkedHashMap<>();
        for (String line : lines) {
            for (MoneyText.TextHit h : MoneyText.textHits(line)) {
                if (first.putIfAbsent(h.code(), line) == null) hits.put(h.code(), h);
            }
        }
        if (first.isEmpty()) return null;
        if (first.size() == 1) {
            String code = first.keySet().iterator().next();
            int max = MoneyText.MIXED.equals(code) ? MIXED_FRAGMENT : FRAGMENT;
            return new TextCurrency(code, List.of(MoneyText.fragment(first.get(code), hits.get(code), max)));
        }
        List<String> fragments = new ArrayList<>();
        for (String code : first.keySet()) {
            if (fragments.size() == 3) break;
            fragments.add(MoneyText.fragment(first.get(code), hits.get(code), MIXED_FRAGMENT));
        }
        return new TextCurrency(MoneyText.MIXED, fragments);
    }

    /**
     * Итоговые строки файла (экран сверки: скидка только в итоге — КП «Рикты» 11.12.2024): строки сетки от «Итого» вниз,
     * начинающиеся с «Итого / Всего / Итог / Total», — число в колонке суммы, иначе наибольшее; абзацы документа так же —
     * наибольшее число, не процент («ВСЕГО К ОПЛАТЕ: 4 единицы товара на сумму 171 343,00 руб.»). Подпись — первая
     * непустая ячейка (абзац — целиком), до {@link #TOTAL_LABEL} символов; строк — до {@link #MAX_TOTALS}.
     */
    static List<ReplyTable.Total> totals(List<List<String>> rows, int end, int width, List<ReplyTable.Column> columns,
                                         List<String> text) {
        List<ReplyTable.Total> out = new ArrayList<>();
        for (int r = end; r < rows.size() && out.size() < MAX_TOTALS; r++) {
            List<String> cells = pad(rows.get(r), width);
            String first = cells.stream().filter(s -> !s.isBlank()).findFirst().orElse("");
            if (!TOTAL.matcher(first).find()) continue;
            BigDecimal v = total(cells, columns);
            if (v != null) out.add(new ReplyTable.Total(label(first), v));
        }
        for (String line : text) {
            if (out.size() >= MAX_TOTALS) break;
            String l = clean(line);
            if (!TOTAL.matcher(l).find()) continue;
            BigDecimal v = largestAmount(l);
            if (v != null) out.add(new ReplyTable.Total(label(l), v));
        }
        return out;
    }

    /** Наибольшее число строки текста, кроме процентов: «… на сумму 171 343,00 руб.», «Итого со скидкой 10%: 90 000». */
    static BigDecimal largestAmount(String line) {
        BigDecimal max = null;
        java.util.regex.Matcher m = TEXT_NUMBER.matcher(line);
        while (m.find()) {
            int i = m.end();
            while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
            if (i < line.length() && line.charAt(i) == '%') continue;
            BigDecimal v = MoneyText.amount(m.group());
            if (v != null && v.signum() > 0 && (max == null || v.compareTo(max) > 0)) max = v;
        }
        return max;
    }

    private static String label(String s) {
        String t = s.strip();
        return t.length() <= TOTAL_LABEL ? t : t.substring(0, TOTAL_LABEL - 1).strip() + "…";
    }

    /** Ячейки строки через пробел; объединённые (тот же текст подряд) — один раз. */
    private static String joined(List<String> row) {
        StringBuilder b = new StringBuilder();
        String prev = null;
        for (String c : row) {
            if (c.isBlank() || c.equals(prev)) continue;
            if (b.length() > 0) b.append(' ');
            b.append(c);
            prev = c;
        }
        return b.toString();
    }

    private static List<String> pad(List<String> row, int width) {
        List<String> out = new ArrayList<>(width);
        for (int c = 0; c < width; c++) out.add(c < row.size() ? row.get(c) : "");
        return out;
    }

    private static String get(List<List<String>> rows, int r, int c) {
        return r < rows.size() && c < rows.get(r).size() ? rows.get(r).get(c) : "";
    }

    private static void set(List<List<String>> rows, int r, int c, String v) {
        List<String> row = rows.get(r);
        while (row.size() <= c) row.add("");
        row.set(c, v);
    }

    /** Переводы строк, табуляция, концы ячеек Word (U+0007) — пробелом; U+0000 вон (PostgreSQL его не хранит). */
    static String clean(String s) {
        if (s == null) return "";
        return s.replace("\0", "").replaceAll("[\\r\\n\\t\\u000B\\u0007]+", " ").replaceAll(" {2,}", " ").trim();
    }
}
