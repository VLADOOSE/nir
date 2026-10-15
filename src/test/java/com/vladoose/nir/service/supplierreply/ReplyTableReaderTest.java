package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyTable;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Таблица КП поставщика из Excel / Word: шапка под бланком, роли, конец по «Итого», шапка в две строки, склейка Word. */
class ReplyTableReaderTest {

    static byte[] res(String name) throws Exception {
        try (InputStream in = ReplyTableReaderTest.class.getResourceAsStream("/supplier-reply/" + name)) {
            return in.readAllBytes();
        }
    }

    static List<LineField> roles(ReplyTable t) {
        return t.columns().stream().map(ReplyTable.Column::role).toList();
    }

    @Test
    void opticsXls_headerUnderLetterhead_twoPricesAndCurrency() throws Exception {
        ReplyTable t = ReplyTableReader.read(res("optics.xls"), ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(t.headerRow()).isEqualTo(15);
        assertThat(roles(t)).containsExactly(LineField.NUMBER, LineField.ARTICLE, LineField.NAME, LineField.QUANTITY,
                LineField.NOTE, LineField.VAT, LineField.PRICE, LineField.PRICE, LineField.CURRENCY);
        assertThat(t.currency()).isEqualTo("RUB");
        ReplyTable.Row first = t.rows().get(0);
        assertThat(first.fileRow()).isEqualTo(16);
        assertThat(first.cells()).containsSequence("1", "MSL24");
        assertThat(first.cells().get(7)).isEqualTo("4700");
    }

    @Test
    void helpXls_noteColumnWithoutHeader() throws Exception {
        ReplyTable t = ReplyTableReader.read(res("help.xls"), ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(t.headerRow()).isEqualTo(12);
        assertThat(roles(t)).contains(LineField.NUMBER, LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM, LineField.NOTE);
        assertThat(t.currency()).isEqualTo("RUB");
        assertThat(t.rows()).anyMatch(r -> r.cells().contains("Заявлены, но отсутствуют"));
    }

    @Test
    void letterLineWithPriceWordIsNotHeader_andTotalStops() throws Exception {
        byte[] xlsx = xlsx(List.of(
                List.of("ТОО «Поставщик»"),
                List.of("Цена договорная, доставка включена"),
                List.of(),
                List.of("№", "Наименование", "Кол-во", "Цена, тг", "Сумма, тг"),
                List.of("1", "Кушетка смотровая", "2", "95000", "190000"),
                List.of("2", "Столик процедурный", "1", "48500", "48500"),
                List.of("Итого", "", "", "", "238500"),
                List.of("3", "Условия: предоплата", "", "", "")));
        ReplyTable t = ReplyTableReader.read(xlsx, ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(t.headerRow()).isEqualTo(4);
        assertThat(t.rows()).hasSize(2);
        assertThat(t.currency()).isEqualTo("KZT");
    }

    @Test
    void textWithoutTable_isEmpty() throws Exception {
        assertThat(ReplyTableReader.read(xlsx(List.of(List.of("Добрый день"), List.of("Цены по запросу"))), ReplyFileKind.EXCEL, Map.of())).isEmpty();
    }

    @Test
    void docx_rfqReturnedWithPrice_letterheadTablesSkipped() throws Exception {
        XWPFDocument d = new XWPFDocument();
        table(d, List.of(List.of("Жауапкершілігі шектеулі серіктестігі", "Товарищество с ограниченной ответственностью")));
        table(d, List.of(List.of("Исх. № 534 от 14.10.2026 г.", "ООО «Поставщик»")));
        table(d, List.of(
                List.of("№", "Наименование", "Характеристики", "Кол-во", "Ед. изм.", "Регистрация в РК", "Цена, тг"),
                List.of("1", "Электрокардиограф ЭК3ТЦ-3/6-04 (Аксион)", "", "1", "шт", "РК-МТ-5№017777", "1 382 400")));
        ReplyTable t = ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow();
        assertThat(t.source()).isEqualTo("таблица 3");
        assertThat(roles(t)).containsExactly(LineField.NUMBER, LineField.NAME, LineField.IGNORE, LineField.QUANTITY,
                LineField.UNIT, LineField.IGNORE, LineField.PRICE);
        assertThat(t.currency()).isEqualTo("KZT");
        assertThat(t.rows()).singleElement().extracting(r -> r.cells().get(6)).isEqualTo("1 382 400");
    }

    @Test
    void docx_twoRowHeader_mergedPrice() throws Exception {
        XWPFDocument d = new XWPFDocument();
        XWPFTable t = d.createTable(3, 5);
        String[][] cells = {{"№", "Наименование", "Кол-во", "Цена, тг", ""}, {"", "", "", "за ед.", "сумма"},
                {"1", "Ширма двухсекционная", "2", "30 000", "60 000"}};
        for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) t.getRow(r).getCell(c).setText(cells[r][c]);
        // «Цена, тг» над двумя колонками: gridSpan 2, лишняя ячейка шапки удаляется
        XWPFTableCell price = t.getRow(0).getCell(3);
        CTTcPr pr = price.getCTTc().isSetTcPr() ? price.getCTTc().getTcPr() : price.getCTTc().addNewTcPr();
        pr.addNewGridSpan().setVal(BigInteger.valueOf(2));
        t.getRow(0).removeCell(4);
        ReplyTable read = ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow();
        assertThat(roles(read)).containsExactly(LineField.NUMBER, LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM);
        assertThat(read.rows()).singleElement().extracting(r -> r.cells().get(3)).isEqualTo("30 000");
    }

    @Test
    void docx_tableSplitAcrossPages_isGlued() throws Exception {
        XWPFDocument d = new XWPFDocument();
        table(d, List.of(List.of("№", "Наименование", "Цена, тг"), List.of("1", "Кушетка", "95 000")));
        table(d, List.of(List.of("2", "Столик", "48 500"), List.of("3", "Ширма", "30 000")));
        ReplyTable t = ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow();
        assertThat(t.rows()).extracting(r -> r.cells().get(1)).containsExactly("Кушетка", "Столик", "Ширма");
    }

    /**
     * Первая строка данных без цифр — не вторая строка шапки. Склейка «Кол-во по запросу», «Цена, тг нет в наличии»
     * роли не меняет, поэтому без правила «под объединённой ячейкой» строку съела бы шапка.
     */
    @Test
    void digitlessFirstRowStaysData() throws Exception {
        ReplyTable t = ReplyTableReader.read(xlsx(List.of(
                List.of("Наименование", "Кол-во", "Цена, тг"),
                List.of("Ширма двухсекционная", "по запросу", "нет в наличии"),
                List.of("Кушетка смотровая", "1", "95 000"))), ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(t.headerRow()).isEqualTo(1);
        assertThat(t.rows()).extracting(r -> r.cells().get(0)).containsExactly("Ширма двухсекционная", "Кушетка смотровая");
    }

    /** Следующая таблица Word со своей шапкой (условия, вторая спецификация) — не продолжение первой. */
    @Test
    void docx_nextTableWithOwnHeaderIsNotGlued() throws Exception {
        XWPFDocument d = new XWPFDocument();
        table(d, List.of(List.of("№", "Наименование", "Цена, тг"), List.of("1", "Кушетка", "95 000")));
        table(d, List.of(List.of("№", "Наименование", "Сумма, тг"), List.of("1", "Доставка", "5 000"), List.of("2", "Монтаж", "7 000")));
        ReplyTable t = ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow();
        // не склеены: выбрана одна таблица целиком (с бо́льшим числом строк), шапка второй не стала строкой данных
        assertThat(t.rows()).extracting(r -> r.cells().get(1)).containsExactly("Доставка", "Монтаж");
    }

    @Test
    void doc_word97Table() throws Exception {
        ReplyTable t = ReplyTableReader.read(res("price-table.doc"), ReplyFileKind.WORD, Map.of()).orElseThrow();
        assertThat(roles(t)).containsExactly(LineField.NUMBER, LineField.NAME, LineField.QUANTITY, LineField.UNIT, LineField.PRICE);
        assertThat(t.rows()).hasSize(3);
        assertThat(t.rows().get(1).cells().get(4)).isEqualTo("48 500,50");
    }

    static byte[] xlsx(List<List<String>> rows) throws Exception {
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

    static void table(XWPFDocument d, List<List<String>> rows) {
        XWPFTable t = d.createTable(rows.size(), rows.get(0).size());
        for (int r = 0; r < rows.size(); r++) for (int c = 0; c < rows.get(r).size(); c++) t.getRow(r).getCell(c).setText(rows.get(r).get(c));
        d.createParagraph();
    }

    static byte[] docx(XWPFDocument d) throws Exception {
        try (d; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.write(out);
            return out.toByteArray();
        }
    }

    /** «Цена, у.е.» — валюта таблицы «у.е.», а не «не названа» (иначе цены встали бы как цены рынка). */
    @Test
    void conventionalUnitsCurrency() throws Exception {
        ReplyTable t = ReplyTableReader.read(xlsx(List.of(List.of("Наименование", "Цена, у.е."), List.of("Монитор пациента", "1200"))),
                ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(t.currency()).isEqualTo("UE");
    }
}
