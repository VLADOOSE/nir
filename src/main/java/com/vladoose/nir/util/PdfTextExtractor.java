package com.vladoose.nir.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

/** Извлечение полного текста из PDF (PDFBox). Нечитаемый/пустой PDF → null, не исключение. */
public final class PdfTextExtractor {

    private PdfTextExtractor() {}

    public static String extract(byte[] pdf) {
        if (pdf == null || pdf.length == 0) return null;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            return (text == null || text.isBlank()) ? null : text;
        } catch (IOException e) {
            return null;
        }
    }
}
