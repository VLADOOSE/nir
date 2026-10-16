package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyTable;
import org.apache.poi.hwpf.HWPFDocument;
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
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Таблица КП поставщика из Excel (.xlsx / .xls) и Word (.docx / .doc) — спека 2026-10-15 §3.2. Шапка — строка с
 * наибольшим числом узнанных заголовков (не меньше двух, среди них наименование и цена или сумма) в первых 40 строках
 * листа или первых 20 строках таблицы Word (форма КП для больниц РК: сверху в той же таблице — 8 строк условий); бланк и обращение над ней отбрасываются; шапка в две строки склеивается;
 * конец таблицы — «Итого» / «Всего» / «Итог»; таблица Word, разорванная по страницам (следующая таблица той же ширины
 * без своей шапки), склеивается. Сбой чтения — исключение: его ловит SupplierReplyFiles.
 */
public final class ReplyTableReader {

    static final int HEADER_SCAN = 40;
    static final int WORD_HEADER_SCAN = 20;
    static final int MAX_ROWS = 2000;
    static final int MAX_COLS = 40;
    private static final Pattern TOTAL = Pattern.compile("^(итого|всего|итог|total)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ReplyTableReader() {}

    /** Строки таблицы как текст; firstRow — номер первой строки в файле (с 1); word — таблица Word (шапка в первых 20 строках). */
    record Grid(String source, List<List<String>> rows, int firstRow, boolean word) {}

    public static Optional<ReplyTable> read(byte[] content, ReplyFileKind kind, Map<String, LineField> learned) throws IOException {
        List<Grid> grids = switch (kind) {
            case EXCEL -> excel(content);
            case WORD -> ole2(content) ? doc(content) : docx(content);
            default -> List.of();
        };
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
                out.add(new Grid(sheet.getSheetName(), rows, 1, false));
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

    static List<Grid> docx(byte[] content) throws IOException {
        List<Grid> out = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
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
                out.add(new Grid("таблица " + n, rows, 1, true));
            }
        }
        return out;
    }

    private static int span(XWPFTableCell cell) {
        CTTcPr pr = cell.getCTTc().getTcPr();
        if (pr == null || pr.getGridSpan() == null || pr.getGridSpan().getVal() == null) return 1;
        return Math.max(1, Math.min(MAX_COLS, pr.getGridSpan().getVal().intValue()));
    }

    static List<Grid> doc(byte[] content) throws IOException {
        List<Grid> out = new ArrayList<>();
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(content))) {
            TableIterator it = new TableIterator(doc.getRange());
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
                out.add(new Grid("таблица " + n, rows, 1, true));
            }
        }
        return out;
    }

    private static int width(Grid g) {
        return g.rows().stream().mapToInt(List::size).max().orElse(0);
    }

    private static Grid glue(Grid a, Grid b) {
        List<List<String>> rows = new ArrayList<>(a.rows());
        rows.addAll(b.rows());
        return new Grid(a.source(), rows, a.firstRow(), a.word());
    }

    // ---- Таблица из сетки -----------------------------------------------------------------------------------------

    static Optional<ReplyTable> table(Grid g, Map<String, LineField> learned) {
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
        for (int r = data; r < rows.size() && out.size() < MAX_ROWS; r++) {
            List<String> cells = pad(rows.get(r), width);
            String first = cells.stream().filter(s -> !s.isBlank()).findFirst().orElse("");
            if (first.isEmpty()) continue;
            if (TOTAL.matcher(first).find()) break;
            out.add(new ReplyTable.Row(g.firstRow() + r, cells));
        }
        List<ReplyTable.Column> columns = new ArrayList<>(width);
        for (int c = 0; c < width; c++) {
            String h = c < header.size() ? header.get(c) : "";
            LineField role = c < roles.size() ? roles.get(c) : LineField.IGNORE;
            if (h.isBlank() && role == LineField.IGNORE && textColumn(out, c)) role = LineField.NOTE;   // решение плана 11
            columns.add(new ReplyTable.Column(c, h, role));
        }
        return Optional.of(new ReplyTable(g.source(), g.firstRow() + best, bestScore, currency(columns, out), columns, out));
    }

    private static List<LineField> roles(List<String> row, Map<String, LineField> learned) {
        List<LineField> out = new ArrayList<>(row.size());
        for (String h : row) out.add(HeaderRoles.role(h, learned));
        return costAsPrice(row, out, learned);
    }

    /**
     * Форма КП для больниц РК: «Стоимость (тенге)» рядом с «Сумма (тенге)» — цена за единицу, а не вторая сумма. Только
     * если колонки цены нет, рядом есть «Сумма» и роль «Стоимости» — догадка правил, а не словарь оператора.
     */
    private static List<LineField> costAsPrice(List<String> header, List<LineField> roles, Map<String, LineField> learned) {
        if (roles.contains(LineField.PRICE)) return roles;
        int cost = -1;
        boolean sum = false;
        for (int c = 0; c < roles.size(); c++) {
            if (roles.get(c) != LineField.SUM) continue;
            String h = HeaderRoles.norm(HeaderRoles.key(header.get(c)));
            if (h.contains("сумм")) sum = true;
            else if (cost < 0 && h.contains("стоимост") && HeaderRoles.learnedRole(header.get(c), learned) == null) cost = c;
        }
        if (cost < 0 || !sum) return roles;
        List<LineField> out = new ArrayList<>(roles);
        out.set(cost, LineField.PRICE);
        return out;
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
