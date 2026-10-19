package com.vladoose.nir.service.readyoffer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Синтетические КП по образцу настоящих (репозиторий публичный — настоящие файлы сюда не кладём). */
public final class ReadyOfferFixtures {

    private ReadyOfferFixtures() {}

    public static final List<String> FATHER_HEADER =
            List.of("№", "Наименование", "Ед. изм.", "Кол-во", "Цена за ед., тг", "НДС", "Общая сумма, тг");

    /** КП «как от 24.09»: «Дата: …», таблица с «ИТОГО». rows — {наименование, кол-во, цена, сумма}. */
    public static byte[] fatherKp(String date, List<String[]> rows, String total) throws Exception {
        XWPFDocument d = new XWPFDocument();
        paragraph(d, "Дата: " + date + " г.");
        List<List<String>> t = new ArrayList<>();
        t.add(FATHER_HEADER);
        int n = 0;
        for (String[] r : rows) t.add(List.of(String.valueOf(++n), r[0], "шт", r[1], r[2], "В том числе НДС 5%", r[3]));
        t.add(List.of("ИТОГО:", "ИТОГО:", "ИТОГО:", "ИТОГО:", "ИТОГО:", "ИТОГО:", total));
        table(d, t);
        return bytes(d);
    }

    public static void paragraph(XWPFDocument d, String text) {
        d.createParagraph().createRun().setText(text);
    }

    public static XWPFTable table(XWPFDocument d, List<List<String>> rows) {
        XWPFTable t = d.createTable(rows.size(), rows.get(0).size());
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < rows.get(r).size(); c++) t.getRow(r).getCell(c).setText(rows.get(r).get(c));
        }
        d.createParagraph();
        return t;
    }

    /** Строка во всю ширину таблицы — объединённая ячейка, как «1 этаж» в КП на консоли «ОЗОН». */
    public static void mergedRow(XWPFTable t, int at, String text, int width) {
        XWPFTableRow row = t.insertNewTableRow(at);
        XWPFTableCell c = row.addNewTableCell();
        c.setText(text);
        c.getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.valueOf(width));
    }

    public static byte[] bytes(XWPFDocument d) throws Exception {
        try (d; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] xlsx(List<List<String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Лист1");
            for (int r = 0; r < rows.size(); r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows.get(r).size(); c++) row.createCell(c).setCellValue(rows.get(r).get(c));
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
