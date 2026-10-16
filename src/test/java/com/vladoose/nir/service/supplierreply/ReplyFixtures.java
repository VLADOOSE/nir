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

    public static byte[] pdfBytes(int pages) throws Exception {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) d.addPage(new PDPage());
            d.save(out);
            return out.toByteArray();
        }
    }
}
