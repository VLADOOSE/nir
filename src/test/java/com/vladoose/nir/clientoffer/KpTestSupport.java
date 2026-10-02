package com.vladoose.nir.clientoffer;

import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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

    private static String normalize(String text) {
        return text.replace('\u00A0', ' ').replaceAll("\\s+", " ");
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
