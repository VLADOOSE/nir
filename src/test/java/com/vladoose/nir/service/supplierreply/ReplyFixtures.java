package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.entity.ReplyTable;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Таблицы и файлы ответа для тестов: без IMAP и без настоящих файлов. */
public final class ReplyFixtures {
    private ReplyFixtures() {}

    /** Таблица «Наименование | Кол-во | Цена» с заданной валютой; строки — пары «наименование, цена». */
    public static ReplyTable table(String currency, String... nameThenPrice) {
        List<ReplyTable.Row> rows = new ArrayList<>();
        for (int i = 0; i < nameThenPrice.length; i += 2) {
            rows.add(new ReplyTable.Row(5 + i / 2, List.of(nameThenPrice[i], "1", nameThenPrice[i + 1])));
        }
        return new ReplyTable("Лист1", 4, 3, currency, List.of(
                new ReplyTable.Column(0, "Наименование", LineField.NAME),
                new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY),
                new ReplyTable.Column(2, "RUB".equals(currency) ? "Цена, руб." : "KZT".equals(currency) ? "Цена, тг" : "Цена", LineField.PRICE)),
                rows);
    }

    public static PreparedFile parsed(String name, ReplyTable t) {
        return new PreparedFile(name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 100L,
                ("zz-" + name + System.nanoTime()).getBytes(), "sha-" + name + "-" + System.nanoTime(),
                ReplyFileKind.EXCEL, ReplyParseStatus.PARSED, null, t, null);
    }

    public static PreparedFile pdf(String name) throws Exception {
        byte[] b = pdfBytes(2);
        return new PreparedFile(name, "application/pdf", (long) b.length, b, "sha-pdf-" + System.nanoTime(),
                ReplyFileKind.PDF, ReplyParseStatus.NOT_PARSED, null, null, 2);
    }

    /** PDF с текстом (шрифт Liberation Sans из ресурсов — кириллица): строки — сверху вниз на одной странице. */
    public static byte[] textPdf(String... lines) throws Exception {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream();
             java.io.InputStream font = ReplyFixtures.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            PDPage page = new PDPage();
            d.addPage(page);
            org.apache.pdfbox.pdmodel.font.PDType0Font f = org.apache.pdfbox.pdmodel.font.PDType0Font.load(d, font);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(d, page)) {
                cs.beginText();
                cs.setFont(f, 11);
                cs.newLineAtOffset(50, 720);
                for (String l : lines) {
                    cs.showText(l);
                    cs.newLineAtOffset(0, -16);
                }
                cs.endText();
            }
            d.save(out);
            return out.toByteArray();
        }
    }

    /** PDF, у которого каждая строка — своя страница (текст на странице N). */
    public static byte[] textPdfPages(String... pages) throws Exception {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream();
             java.io.InputStream font = ReplyFixtures.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            org.apache.pdfbox.pdmodel.font.PDType0Font f = org.apache.pdfbox.pdmodel.font.PDType0Font.load(d, font);
            for (String text : pages) {
                PDPage page = new PDPage();
                d.addPage(page);
                try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(d, page)) {
                    cs.beginText();
                    cs.setFont(f, 11);
                    cs.newLineAtOffset(50, 720);
                    cs.showText(text);
                    cs.endText();
                }
            }
            d.save(out);
            return out.toByteArray();
        }
    }

    public static byte[] pdfBytes(int pages) throws Exception {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) d.addPage(new PDPage());
            d.save(out);
            return out.toByteArray();
        }
    }
}
