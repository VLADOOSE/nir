package com.vladoose.nir.clientoffer;

import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Проверки документов КП: текст и картинки PDF, «ловушка» внешних запросов, тестовые картинки. */
final class KpTestSupport {

    private KpTestSupport() {}

    /**
     * Весь текст PDF одной строкой: любые пробельные — один пробел. В ячейках длинный текст переносится, и PDF отдаёт
     * его с переводом строки («Цена за ед.,\nтг»); неразрывные пробелы между разрядами — обычные («126 000,00»).
     */
    static String text(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return normalize(new PDFTextStripper().getText(d));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> pageTexts(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            List<String> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 1; i <= d.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pages.add(normalize(stripper.getText(d)));
            }
            return pages;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Строки текста PDF по порядку, без пустых: внутри строки пробельные — один пробел, края обрезаны. В отличие от
     * {@link #text} переводы строк сохраняются — так видно, что абзац вышел двумя строками, а не склеился в одну.
     */
    static List<String> lines(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            List<String> lines = new ArrayList<>();
            for (String line : new PDFTextStripper().getText(d).split("\\R")) {
                String t = normalize(line).trim();
                if (!t.isEmpty()) lines.add(t);
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalize(String text) {
        return text.replace('\u00A0', ' ').replaceAll("\\s+", " ");
    }

    /** Где напечатан текст: страница (с 0), края в мм от левого верхнего угла листа, шрифт и кегль (pt) первого знака —
     * по матрице вывода текста (getFontSizeInPt у PDFBox округляет вниз до целого: 8 pt читается как 7). */
    record Placed(int page, float left, float top, float right, float bottom, String font, float sizePt) {}

    /** Прямоугольник картинки: страница (с 0), края в мм от левого верхнего угла листа. */
    record Box(int page, float left, float top, float right, float bottom) {
        float width() {
            return right - left;
        }

        float height() {
            return bottom - top;
        }

        boolean overlaps(Placed t) {
            return page == t.page() && left < t.right() && t.left() < right && top < t.bottom() && t.top() < bottom;
        }
    }

    /**
     * Первое вхождение текста в PDF — для проверок вёрстки (выравнивание, ширины, жирный). Пробелы, в том числе
     * неразрывные, не сравниваются: PDF не обязан рисовать их знаками. Текст, перенесённый на несколько строк,
     * находится тоже — края тогда охватывают все его строки.
     */
    static Placed find(byte[] pdf, String needle) {
        List<Placed> all = findAll(pdf, needle);
        if (all.isEmpty()) throw new AssertionError("в PDF нет текста «" + needle + "»");
        return all.get(0);
    }

    /** Все вхождения текста в PDF, в порядке вывода (одно число бывает и в цене, и в сумме), — как {@link #find}. */
    static List<Placed> findAll(byte[] pdf, String needle) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            StringBuilder chars = new StringBuilder();
            List<TextPosition> glyphs = new ArrayList<>();
            List<Integer> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    for (char c : text.getUnicode().toCharArray()) {
                        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) continue;
                        chars.append(c);
                        glyphs.add(text);
                        pages.add(getCurrentPageNo() - 1);
                    }
                }
            };
            stripper.getText(d);
            String target = needle.replaceAll("[\\s\\u00A0]", "");
            List<Placed> found = new ArrayList<>();
            for (int at = chars.indexOf(target); at >= 0; at = chars.indexOf(target, at + 1)) {
                float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
                for (TextPosition g : glyphs.subList(at, at + target.length())) {
                    left = Math.min(left, g.getXDirAdj());
                    right = Math.max(right, g.getXDirAdj() + g.getWidthDirAdj());
                    top = Math.min(top, g.getYDirAdj() - g.getHeightDir());
                    bottom = Math.max(bottom, g.getYDirAdj());
                }
                TextPosition first = glyphs.get(at);
                found.add(new Placed(pages.get(at), mm(left), mm(top), mm(right), mm(bottom), first.getFont().getName(),
                        first.getXScale()));
            }
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Где нарисованы растровые картинки — в порядке рисования, с вложенными формами. */
    static List<Box> imageBoxes(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            List<Box> boxes = new ArrayList<>();
            for (int i = 0; i < d.getNumberOfPages(); i++) {
                int page = i;
                float height = d.getPage(i).getMediaBox().getHeight();
                PDFStreamEngine engine = new PDFStreamEngine() {
                    {
                        addOperator(new Concatenate(this));
                        addOperator(new Save(this));
                        addOperator(new Restore(this));
                        addOperator(new DrawObject(this));   // формы — внутрь, картинки ловит processOperator
                    }

                    @Override
                    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
                        if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName name
                                && getResources().getXObject(name) instanceof PDImageXObject) {
                            // картинка — единичный квадрат, растянутый текущей матрицей
                            Matrix m = getGraphicsState().getCurrentTransformationMatrix();
                            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                            for (float[] corner : new float[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}}) {
                                Point2D.Float p = m.transformPoint(corner[0], corner[1]);
                                minX = Math.min(minX, p.x);
                                maxX = Math.max(maxX, p.x);
                                minY = Math.min(minY, p.y);
                                maxY = Math.max(maxY, p.y);
                            }
                            boxes.add(new Box(page, mm(minX), mm(height - maxY), mm(maxX), mm(height - minY)));
                        } else {
                            super.processOperator(operator, operands);
                        }
                    }
                };
                engine.processPage(d.getPage(i));
            }
            return boxes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Залитые и обведённые фигуры — рамки, линии (в том числе линия подписи): прямоугольник каждой, в мм от левого
     * верхнего угла листа. Точки пути PDFBox уже переводит текущей матрицей в координаты страницы.
     */
    static List<Box> shapeBoxes(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            List<Box> boxes = new ArrayList<>();
            for (int i = 0; i < d.getNumberOfPages(); i++) {
                int page = i;
                float height = d.getPage(i).getMediaBox().getHeight();
                PDFGraphicsStreamEngine engine = new PDFGraphicsStreamEngine(d.getPage(i)) {
                    private final GeneralPath path = new GeneralPath();

                    @Override
                    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
                        path.moveTo(p0.getX(), p0.getY());
                        path.lineTo(p1.getX(), p1.getY());
                        path.lineTo(p2.getX(), p2.getY());
                        path.lineTo(p3.getX(), p3.getY());
                        path.closePath();
                    }

                    @Override
                    public void moveTo(float x, float y) {
                        path.moveTo(x, y);
                    }

                    @Override
                    public void lineTo(float x, float y) {
                        path.lineTo(x, y);
                    }

                    @Override
                    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
                        path.curveTo(x1, y1, x2, y2, x3, y3);
                    }

                    @Override
                    public Point2D getCurrentPoint() {
                        return path.getCurrentPoint();
                    }

                    @Override
                    public void closePath() {
                        path.closePath();
                    }

                    @Override
                    public void endPath() {
                        path.reset();
                    }

                    @Override
                    public void strokePath() {
                        emit();
                    }

                    @Override
                    public void fillPath(int windingRule) {
                        emit();
                    }

                    @Override
                    public void fillAndStrokePath(int windingRule) {
                        emit();
                    }

                    @Override
                    public void clip(int windingRule) {
                    }

                    @Override
                    public void drawImage(PDImage pdImage) {
                    }

                    @Override
                    public void shadingFill(COSName shadingName) {
                    }

                    private void emit() {
                        if (path.getCurrentPoint() != null) {
                            Rectangle2D r = path.getBounds2D();
                            boxes.add(new Box(page, mm((float) r.getMinX()), mm(height - (float) r.getMaxY()),
                                    mm((float) r.getMaxX()), mm(height - (float) r.getMinY())));
                        }
                        path.reset();
                    }
                };
                engine.processPage(d.getPage(i));
            }
            return boxes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static float mm(float pt) {
        return pt * 25.4f / 72f;
    }

    /** Сколько растровых картинок нарисовано на страницах (с вложенными формами). */
    static int imageCount(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            int n = 0;
            for (PDPage page : d.getPages()) n += images(page.getResources());
            return n;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int images(PDResources resources) throws IOException {
        if (resources == null) return 0;
        int n = 0;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject x = resources.getXObject(name);
            if (x instanceof PDImageXObject) n++;
            else if (x instanceof PDFormXObject form) n += images(form.getResources());
        }
        return n;
    }

    static PDRectangle firstPageSize(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return d.getPage(0).getMediaBox();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** PNG 200×200: синий круг на прозрачном фоне — «печать». */
    static byte[] circlePng() {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(30, 60, 200));
        g.setStroke(new BasicStroke(8));
        g.drawOval(8, 8, 184, 184);
        g.dispose();
        return png(img);
    }

    /**
     * PNG 300×80: росчерк — «подпись». Картинка ДРУГАЯ, чем у печати: и openhtmltopdf (кеш по URI), и POI
     * (повторное использование одинаковых данных) склеивают одинаковые картинки в одну — счёт «печать + подпись»
     * на одинаковых байтах дал бы 1 вместо 2.
     */
    static byte[] signaturePng() {
        BufferedImage img = new BufferedImage(300, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(20, 30, 120));
        g.setStroke(new BasicStroke(4));
        g.drawLine(10, 60, 120, 15);
        g.drawLine(120, 15, 180, 65);
        g.drawLine(180, 65, 290, 20);
        g.dispose();
        return png(img);
    }

    /** JPEG: закрашенный синий круг на белом листе — как скан печати. */
    static byte[] circleOnWhiteJpeg(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);
        g.setColor(new Color(30, 60, 200));
        g.fillOval(size / 4, size / 4, size / 2, size / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "jpeg", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] png(BufferedImage img) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Локальный HTTP-сервер, считающий обращения: документ не должен ходить никуда. */
    static final class TrapServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger hits = new AtomicInteger();

        TrapServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                hits.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        int hits() {
            return hits.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
