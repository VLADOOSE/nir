package com.vladoose.nir.service.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Страницы PDF → PNG для предпросмотра: оператор видит ровно то, что получит клиент (спека §8.3). */
@Component
public class KpPreviewRenderer {

    /** 110 dpi: страница A4 ≈ 909×1286 px, 150–250 КБ — читаемо и на телефоне, и на 1280. */
    public static final float DPI = 110f;

    public List<byte[]> pages(byte[] pdf, int maxPages) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int count = Math.min(document.getNumberOfPages(), maxPages);
            List<byte[]> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, DPI, ImageType.RGB);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(image, "png", png);
                result.add(png.toByteArray());
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Предпросмотр не собран", e);
        }
    }
}
