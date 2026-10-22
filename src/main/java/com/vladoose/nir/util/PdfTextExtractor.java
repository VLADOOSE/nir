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

    /** Текст первой страницы (номер и дата КП — в бланке); нечитаемый или пустой — null. */
    public static String firstPage(byte[] pdf) {
        return firstPages(pdf, 1);
    }

    /** Текст первых {@code max} страниц (каталог на сотни страниц целиком не читаем); нечитаемый или пустой — null. */
    public static String firstPages(byte[] pdf, int max) {
        if (pdf == null || pdf.length == 0) return null;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(1);
            s.setEndPage(Math.max(1, max));
            String text = s.getText(doc);
            return (text == null || text.isBlank()) ? null : text;
        } catch (IOException e) {
            return null;
        }
    }
}
