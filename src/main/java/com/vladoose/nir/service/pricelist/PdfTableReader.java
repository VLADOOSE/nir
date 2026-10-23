package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyCurrencySource;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.supplierreply.HeaderRoles;
import com.vladoose.nir.service.supplierreply.MoneyText;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.*;

/**
 * PDF-таблица по сетке (спека 2026-10-23 §5.2 п. 3, решение плана 7). Прайс, выгруженный из Calc / Excel / Word, рисует
 * рамки ячеек: вертикальные линии — колонки, горизонтальные — строки; текст ячейки — символы внутри её прямоугольника.
 * Объединённая ячейка (раздел во всю ширину, РУ на несколько строк) — там, где линии нет; её текст получают все строки,
 * которые она накрывает. Колонки — по каждой странице, роли — по шапке первой страницы с шапкой, по порядку колонок.
 * Страница с другим числом колонок — не продолжение таблицы. Текст вне сетки — для шапки прайса и условий.
 */
public final class PdfTableReader {

    public record Result(ReplyTable table, List<String> outside) {}

    /** Символ: x слева, y — базовая линия от верха страницы (TextPosition.getYDirAdj), ширина, высота, ширина пробела шрифта. */
    record Glyph(String ch, float x, float y, float w, float h, float space) {
        float cx() { return x + w / 2; }
        float cy() { return y - h / 2; }
    }

    /** Отрезок сетки в координатах «сверху»: вертикальный — x = at, y от from до to; горизонтальный — y = at, x от from до to. */
    record Seg(boolean vertical, float at, float from, float to) {}

    static final int MAX_PAGES = 30;
    static final float JOIN = 1.5f;
    static final float MIN_SEG = 4f;
    static final float THIN = 2f;

    private PdfTableReader() {}

    public static Optional<Result> read(byte[] pdf, Map<String, LineField> learned) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int pages = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            List<String> headers = null;
            List<LineField> roles = null;
            List<ReplyTable.Row> rows = new ArrayList<>();
            List<String> outside = new ArrayList<>();
            int rowNo = 0;
            for (int p = 0; p < pages; p++) {
                PDPage page = doc.getPage(p);
                List<Glyph> glyphs = glyphs(doc, p + 1);
                Grid g = Grid.of(segments(page));
                if (g == null) {
                    outside.addAll(lines(glyphs));
                    continue;
                }
                outside.addAll(lines(g.outside(glyphs)));
                List<List<String>> cells = g.cells(glyphs);
                int start;
                if (headers == null) {
                    int h = header(cells, learned);
                    if (h < 0) continue;
                    headers = cells.get(h);
                    roles = headers.stream().map(x -> HeaderRoles.role(x, learned)).toList();
                    start = h + 1;
                } else if (cells.isEmpty() || cells.get(0).size() != headers.size()) {
                    continue;
                } else {
                    start = header(cells, learned) == 0 ? 1 : 0;              // шапка повторена на странице
                }
                for (int r = start; r < cells.size(); r++) {
                    List<String> row = cells.get(r);
                    if (row.stream().allMatch(String::isBlank)) continue;
                    rows.add(new ReplyTable.Row(++rowNo, row));
                }
            }
            if (headers == null) return Optional.empty();
            List<ReplyTable.Column> cols = new ArrayList<>();
            String currency = null;
            for (int c = 0; c < headers.size(); c++) {
                cols.add(new ReplyTable.Column(c, headers.get(c), roles.get(c)));
                if (currency == null && roles.get(c) == LineField.PRICE) currency = MoneyText.currency(headers.get(c));
            }
            ReplyTable.CurrencyFrom from = new ReplyTable.CurrencyFrom(
                    currency == null ? ReplyCurrencySource.NONE : ReplyCurrencySource.TABLE, List.of(), null, null);
            int score = (int) roles.stream().filter(x -> x != LineField.IGNORE).count();
            return Optional.of(new Result(new ReplyTable("PDF", 1, score, currency, cols, rows, from, null), outside));
        }
    }

    /** Шапка — среди первых 5 строк сетки: строка с наименованием и ценой (как у таблиц Excel), иначе −1. */
    static int header(List<List<String>> cells, Map<String, LineField> learned) {
        for (int r = 0; r < Math.min(5, cells.size()); r++) {
            Set<LineField> rs = EnumSet.noneOf(LineField.class);
            for (String c : cells.get(r)) rs.add(HeaderRoles.role(c, learned));
            if (rs.contains(LineField.NAME) && rs.contains(LineField.PRICE)) return r;
        }
        return -1;
    }

    // ---- текст ----------------------------------------------------------------------------------------------------

    static List<Glyph> glyphs(PDDocument doc, int pageNo) throws IOException {
        List<Glyph> out = new ArrayList<>();
        PDFTextStripper s = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                for (TextPosition t : positions) {
                    String u = t.getUnicode();
                    if (u == null || u.isBlank()) continue;
                    out.add(new Glyph(u, t.getXDirAdj(), t.getYDirAdj(), t.getWidthDirAdj(), Math.max(t.getHeightDir(), 1f),
                            t.getWidthOfSpace()));
                }
            }
        };
        s.setSortByPosition(true);
        s.setStartPage(pageNo);
        s.setEndPage(pageNo);
        s.getText(doc);
        return out;
    }

    /** Символы → строки текста сверху вниз (одна строка — символы на одной высоте, ±0,6 высоты). */
    static List<String> lines(List<Glyph> glyphs) {
        List<String> out = new ArrayList<>();
        for (List<Glyph> line : group(glyphs)) {
            String t = join(line);
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    /** Текст ячейки: строки ячейки через пробел; строка, кончившаяся дефисом, склеивается со следующей без пробела. */
    static String cellText(List<Glyph> glyphs) {
        StringBuilder b = new StringBuilder();
        for (List<Glyph> line : group(glyphs)) {
            String t = join(line);
            if (t.isEmpty()) continue;
            if (!b.isEmpty() && b.charAt(b.length() - 1) != '-') b.append(' ');
            b.append(t);
        }
        return b.toString().strip();
    }

    private static List<List<Glyph>> group(List<Glyph> glyphs) {
        List<Glyph> sorted = new ArrayList<>(glyphs);
        sorted.sort(Comparator.comparingDouble(Glyph::y).thenComparingDouble(Glyph::x));
        List<List<Glyph>> lines = new ArrayList<>();
        for (Glyph g : sorted) {
            List<Glyph> last = lines.isEmpty() ? null : lines.get(lines.size() - 1);
            if (last != null && Math.abs(last.get(0).y() - g.y()) <= 0.6f * Math.max(g.h(), last.get(0).h())) last.add(g);
            else lines.add(new ArrayList<>(List.of(g)));
        }
        for (List<Glyph> l : lines) l.sort(Comparator.comparingDouble(Glyph::x));
        return lines;
    }

    private static String join(List<Glyph> line) {
        StringBuilder b = new StringBuilder();
        Glyph prev = null;
        for (Glyph g : line) {
            if (prev != null) {
                float gap = g.x() - (prev.x() + prev.w());
                float space = prev.space() > 0 ? prev.space() : prev.h() * 0.5f;
                if (gap > space * 0.5f) b.append(' ');
            }
            b.append(g.ch());
            prev = g;
        }
        return b.toString().replaceAll("\\s+", " ").strip();
    }

    // ---- сетка ----------------------------------------------------------------------------------------------------

    /** Линии страницы: обведённые отрезки и стороны прямоугольников, а залитые — только тонкие (тоньше 2 pt) — рамки. */
    static List<Seg> segments(PDPage page) throws IOException {
        PDRectangle box = page.getMediaBox();
        float top = box.getLowerLeftY() + box.getHeight();
        List<Seg> out = new ArrayList<>();
        PDFGraphicsStreamEngine e = new PDFGraphicsStreamEngine(page) {
            private final List<float[]> path = new ArrayList<>();
            private Point2D cur;
            private Point2D start;

            private void edge(Point2D p, Point2D q) {
                path.add(new float[]{(float) p.getX(), (float) p.getY(), (float) q.getX(), (float) q.getY()});
            }

            @Override public void appendRectangle(Point2D a, Point2D b, Point2D c, Point2D d) {
                edge(a, b); edge(b, c); edge(c, d); edge(d, a);
            }
            @Override public void moveTo(float x, float y) { cur = new Point2D.Float(x, y); start = cur; }
            @Override public void lineTo(float x, float y) {
                Point2D n = new Point2D.Float(x, y);
                if (cur != null) edge(cur, n);
                cur = n;
            }
            @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { cur = new Point2D.Float(x3, y3); }
            @Override public Point2D getCurrentPoint() { return cur; }
            @Override public void closePath() { if (cur != null && start != null) edge(cur, start); cur = start; }
            @Override public void endPath() { path.clear(); }
            @Override public void strokePath() { commit(false); }
            @Override public void fillPath(int windingRule) { commit(true); }
            @Override public void fillAndStrokePath(int windingRule) { commit(false); }
            @Override public void drawImage(PDImage pdImage) { }
            @Override public void clip(int windingRule) { }
            @Override public void shadingFill(COSName shadingName) { }

            private void commit(boolean fill) {
                if (fill) {
                    // заливка — рамка, только если вся фигура тонкая (линия, нарисованная прямоугольником); фон ячеек — нет
                    float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                    for (float[] s : path) {
                        minX = Math.min(minX, Math.min(s[0], s[2])); maxX = Math.max(maxX, Math.max(s[0], s[2]));
                        minY = Math.min(minY, Math.min(s[1], s[3])); maxY = Math.max(maxY, Math.max(s[1], s[3]));
                    }
                    if (!path.isEmpty()) {
                        if (maxX - minX < THIN && maxY - minY >= MIN_SEG) out.add(new Seg(true, (minX + maxX) / 2, top - maxY, top - minY));
                        else if (maxY - minY < THIN && maxX - minX >= MIN_SEG) out.add(new Seg(false, top - (minY + maxY) / 2, minX, maxX));
                    }
                } else {
                    for (float[] s : path) {
                        if (Math.abs(s[0] - s[2]) < 0.5f && Math.abs(s[1] - s[3]) >= MIN_SEG) {
                            out.add(new Seg(true, s[0], top - Math.max(s[1], s[3]), top - Math.min(s[1], s[3])));
                        } else if (Math.abs(s[1] - s[3]) < 0.5f && Math.abs(s[0] - s[2]) >= MIN_SEG) {
                            out.add(new Seg(false, top - s[1], Math.min(s[0], s[2]), Math.max(s[0], s[2])));
                        }
                    }
                }
                path.clear();
            }
        };
        e.processPage(page);
        return out;
    }

    /** Сетка страницы: границы колонок xs, границы строк ys (все), у каждой колонки — свои границы (объединённые ячейки). */
    record Grid(float[] xs, float[] ys, List<float[]> columnYs) {

        static Grid of(List<Seg> segs) {
            float[] xs = cluster(segs.stream().filter(Seg::vertical).map(Seg::at).toList());
            if (xs.length < 3) return null;
            List<Seg> h = segs.stream().filter(s -> !s.vertical()).toList();
            List<Float> ys = new ArrayList<>();
            List<float[]> columnYs = new ArrayList<>();
            for (int c = 0; c + 1 < xs.length; c++) {
                List<Float> own = new ArrayList<>();
                for (Seg s : h) if (s.from() <= xs[c] + JOIN && s.to() >= xs[c + 1] - JOIN) own.add(s.at());
                ys.addAll(own);
                columnYs.add(cluster(own));
            }
            float[] all = cluster(ys);
            return all.length < 2 ? null : new Grid(xs, all, columnYs);
        }

        List<Glyph> outside(List<Glyph> glyphs) {
            float topY = ys[0], bottomY = ys[ys.length - 1], left = xs[0], right = xs[xs.length - 1];
            return glyphs.stream().filter(g -> g.cy() < topY || g.cy() > bottomY || g.cx() < left || g.cx() > right).toList();
        }

        /** Ячейки строк сетки: у колонки — её собственные границы; текст объединённой ячейки — каждой строке под ней. */
        List<List<String>> cells(List<Glyph> glyphs) {
            int nr = ys.length - 1, nc = xs.length - 1;
            List<List<String>> out = new ArrayList<>();
            for (int r = 0; r < nr; r++) out.add(new ArrayList<>(Collections.nCopies(nc, "")));
            for (int c = 0; c < nc; c++) {
                float[] cb = columnYs.get(c).length < 2 ? ys : columnYs.get(c);
                float x1 = xs[c], x2 = xs[c + 1];
                for (int k = 0; k + 1 < cb.length; k++) {
                    float y1 = cb[k], y2 = cb[k + 1];
                    List<Glyph> in = glyphs.stream().filter(g -> g.cx() > x1 && g.cx() < x2 && g.cy() > y1 && g.cy() < y2).toList();
                    if (in.isEmpty()) continue;
                    String text = cellText(in);
                    for (int r = 0; r < nr; r++) {
                        float mid = (ys[r] + ys[r + 1]) / 2;
                        if (mid > y1 && mid < y2) out.get(r).set(c, text);
                    }
                }
            }
            return out;
        }
    }

    /** Координаты линий — по возрастанию, ближе JOIN — одна (среднее). */
    static float[] cluster(List<Float> values) {
        List<Float> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        List<Float> out = new ArrayList<>();
        float sum = 0;
        int n = 0;
        for (Float v : sorted) {
            if (n > 0 && v - sum / n > JOIN) {
                out.add(sum / n);
                sum = 0;
                n = 0;
            }
            sum += v;
            n++;
        }
        if (n > 0) out.add(sum / n);
        float[] a = new float[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }
}
