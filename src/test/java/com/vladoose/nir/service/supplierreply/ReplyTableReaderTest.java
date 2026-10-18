package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyTable;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;

import java.io.ByteArrayInputStream;
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

    /**
     * Форма КП для больниц РК: «Стоимость (тенге)» рядом с «Сумма (тенге)» — цена за единицу. Одна «Стоимость» — по-прежнему
     * сумма; роль из словаря оператора правило не перебивает.
     */
    @Test
    void costNextToSumIsUnitPrice() throws Exception {
        byte[] xlsx = xlsx(List.of(
                List.of("№", "Наименование медицинской техники", "Кол-во (ед. изм.)", "Стоимость (тенге)", "Сумма (тенге)"),
                List.of("1", "Аппарат электрохирургический ЭХВЧ-350-03", "2 шт", "3 450 000,00", "6 900 000,00")));
        ReplyTable t = ReplyTableReader.read(xlsx, ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(roles(t)).containsExactly(LineField.NUMBER, LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM);
        assertThat(ReplyRowValues.of(t).get(0).price()).isEqualByComparingTo("3450000");

        ReplyTable learned = ReplyTableReader.read(xlsx, ReplyFileKind.EXCEL, Map.of("стоимость (тенге)", LineField.SUM)).orElseThrow();
        assertThat(roles(learned).get(3)).isEqualTo(LineField.SUM);

        ReplyTable alone = ReplyTableReader.read(xlsx(List.of(List.of("Наименование", "Стоимость, тг"), List.of("Ширма", "30 000"))),
                ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(roles(alone)).containsExactly(LineField.NAME, LineField.SUM);
    }

    /**
     * «Стоимость» — цена за единицу, только когда в заголовке нет признаков суммы или другой величины (ревью ветки
     * курса): «Стоимость без НДС», «Общая стоимость», «Стоимость всего», «Итоговая стоимость», «Стоимость доставки» рядом
     * с «Суммой» — по-прежнему суммы.
     */
    @Test
    void costWithTotalOrVatOrDelivery_staysSum() throws Exception {
        for (String cost : List.of("Стоимость без НДС", "Общая стоимость", "Стоимость всего", "Итоговая стоимость", "Стоимость доставки")) {
            ReplyTable t = ReplyTableReader.read(xlsx(List.of(
                    List.of("№", "Наименование", "Кол-во", cost, "Сумма с НДС"),
                    List.of("1", "Аппарат электрохирургический ЭХВЧ-350-03", "2", "6 160 714,29", "6 900 000,00"))),
                    ReplyFileKind.EXCEL, Map.of()).orElseThrow();
            assertThat(roles(t)).as(cost).containsExactly(LineField.NUMBER, LineField.NAME, LineField.QUANTITY, LineField.SUM, LineField.SUM);
        }
    }

    /**
     * Форма КП для больниц РК в Word: сверху в той же таблице — условия (дата КП, условия поставки, гарантия…, по две
     * ячейки), шапка позиций — 9-я строка. Шапку раньше искали только в первых 5 строках таблицы — таблица не находилась.
     */
    @Test
    void docx_hospitalForm_conditionsAboveHeader() throws Exception {
        XWPFDocument d = new XWPFDocument();
        List<List<String>> conditions = List.of(
                List.of("Дата коммерческого предложения", "«25» февраля 2026 г."), List.of("Условия поставки", "DDP Заказчик"),
                List.of("Гарантия", "37 месяцев"), List.of("Обучение", "Включено в стоимость"),
                List.of("Срок действия предложения", "До «31» декабря 2026 года"), List.of("Срок поставки", "Согласно условиям договора"),
                List.of("Сведения о регистрации медицинской техники", "РК-МТ-5№016696 от 28.06.2021 г."), List.of("", ""));
        XWPFTable t = d.createTable(conditions.size() + 4, 6);
        for (int r = 0; r < conditions.size(); r++) {
            for (int c = 5; c >= 2; c--) t.getRow(r).removeCell(c);
            t.getRow(r).getCell(0).setText(conditions.get(r).get(0));
            t.getRow(r).getCell(1).setText(conditions.get(r).get(1));
        }
        List<List<String>> body = List.of(
                List.of("№", "Наименование медицинской техники (по регистрационному удостоверению)", "Кол-во (ед. изм.)",
                        "Стоимость (тенге)", "Сумма (тенге)", "Сведения о регистрации"),
                List.of("1", "Аппарат электрохирургический высокочастотный ЭХВЧ-350-03", "1 шт", "6 900 000,00", "6 900 000,00", "РК-МТ-7№014847"),
                List.of("Гарантийное сервисное обслуживание 37 месяцев", "Включено в стоимость медицинской техники", "", "", "", ""),
                List.of("Итого сумма", "6 900 000,00", "", "", "", ""));
        for (int r = 0; r < body.size(); r++) for (int c = 0; c < 6; c++) t.getRow(conditions.size() + r).getCell(c).setText(body.get(r).get(c));
        ReplyTable rt = ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow();
        assertThat(rt.headerRow()).isEqualTo(9);
        assertThat(roles(rt).subList(0, 5)).containsExactly(LineField.NUMBER, LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM);
        assertThat(ReplyRowValues.of(rt).stream().filter(ReplyRowValues::participates).toList())
                .singleElement().satisfies(v -> {
                    assertThat(v.name()).contains("ЭХВЧ-350-03");
                    assertThat(v.price()).isEqualByComparingTo("6900000");
                });
    }

    /**
     * КП, где валюты в таблице нет, а названа она только в абзаце под таблицей («на сумму … руб.»): без этого таблица
     * считалась в валюте запроса, и на запросе West-Med рубли встали бы тенге (в 6 раз дешевле).
     */
    @Test
    void docx_currencyOnlyInParagraphUnderTable() throws Exception {
        XWPFDocument d = new XWPFDocument();
        d.createParagraph().createRun().setText("Направляем предложение на поставку оборудования:");
        table(d, List.of(List.of("№", "Наименование", "Цена за единицу", "НДС", "Коли-чество", "Сумма"),
                List.of("1.", "Облучатель ультрафиолетовый настенный", "18 450,00", "Не облагается", "1", "18 450,00"),
                List.of("ИТОГО", "", "", "", "1", "18 450,00")));
        d.createParagraph().createRun().setText("Общая стоимость предложения — 18 450,00 руб., НДС не облагается.");
        ReplyTable t = ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow();
        assertThat(t.currency()).isEqualTo("RUB");
        assertThat(ReplyRowValues.of(t).get(0).currency()).isEqualTo("RUB");
    }

    /** В тексте документа разные валюты — не угадывать: таблица «валюта не ясна», цены сами не подставятся. */
    @Test
    void docx_differentCurrenciesInText_mixed() throws Exception {
        XWPFDocument d = new XWPFDocument();
        table(d, List.of(List.of("Наименование", "Кол-во", "Цена"), List.of("Облучатель ультрафиолетовый настенный", "1", "18 450,00")));
        d.createParagraph().createRun().setText("Цены указаны в рублях.");
        d.createParagraph().createRun().setText("Оплата в тенге по курсу на день оплаты.");
        ReplyTable t = ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow();
        assertThat(t.currency()).isEqualTo(MoneyText.MIXED);
    }

    /** Валюта в шапке таблицы главнее текста вокруг. */
    @Test
    void docx_tableCurrencyWinsOverText() throws Exception {
        XWPFDocument d = new XWPFDocument();
        table(d, List.of(List.of("Наименование", "Цена, тг"), List.of("Кушетка", "95 000")));
        d.createParagraph().createRun().setText("Доставка — 15 000 руб.");
        assertThat(ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow().currency()).isEqualTo("KZT");
    }

    /**
     * Форма КП для больниц РК: строки условий — в той же таблице над шапкой позиций, валюта названа в них («оплата в
     * тенге»), в шапке её нет.
     */
    @Test
    void docx_currencyInConditionRowsAboveHeader() throws Exception {
        XWPFDocument d = new XWPFDocument();
        table(d, List.of(
                List.of("Дата коммерческого предложения", "«2» марта 2026 г.", "", "", "", ""),
                List.of("Условия поставки", "DDP Заказчик", "", "", "", ""),
                List.of("Условия оплаты", "оплата в тенге по факту поставки", "", "", "", ""),
                List.of("Срок поставки", "Согласно условиям договора", "", "", "", ""),
                List.of("№", "Наименование", "Кол-во (ед. изм.)", "Стоимость", "Сумма", "Сведения о регистрации"),
                List.of("1", "Облучатель ультрафиолетовый настенный", "2 шт", "18 450,00", "36 900,00", "РК МИ (МТ)-0№000001"),
                List.of("Итого", "", "", "", "36 900,00", "")));
        ReplyTable t = ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow();
        assertThat(t.headerRow()).isEqualTo(5);
        assertThat(t.currency()).isEqualTo("KZT");
        assertThat(ReplyRowValues.of(t).get(0).price()).isEqualByComparingTo("18450");
    }

    /**
     * Абзац Word с разрывом строки: реквизиты вырезаются до конца своей строки, а не всего абзаца — фраза о ценах на
     * следующей строке абзаца остаётся.
     */
    @Test
    void docx_paragraphLineBreak_requisitesOnTheirLine() throws Exception {
        XWPFDocument d = new XWPFDocument();
        table(d, List.of(List.of("Наименование", "Кол-во", "Цена"), List.of("Кушетка смотровая", "1", "30 000")));
        org.apache.poi.xwpf.usermodel.XWPFRun r = d.createParagraph().createRun();
        r.setText("Р/с 40702810400000012345 в ПАО «Банк»");
        r.addBreak();
        r.setText("Цены указаны в рублях");
        assertThat(ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow().currency()).isEqualTo("RUB");
    }

    /**
     * Excel: валюта в строке «Итого», на которой таблица кончается (сумма и «тенге» — в разных ячейках); строки данных не
     * в счёт — «доставка 1 500 руб.» в наименовании не валюта таблицы.
     */
    @Test
    void xlsx_currencyInTotalRow_dataRowsNotScanned() throws Exception {
        ReplyTable t = ReplyTableReader.read(xlsx(List.of(
                List.of("Наименование", "Кол-во", "Цена", "Сумма"),
                List.of("Кабель удлинитель (с доставкой 1 500 руб.)", "2", "1 500", "3 000"),
                List.of("Итого", "", "3 000", "тенге"))), ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(t.currency()).isEqualTo("KZT");
    }

    /**
     * Таблица длиннее предела строк (2000): оборвались на пределе — остаток тоже строки данных и в текст вне таблицы не
     * идёт («доставка 1 500 руб.» в наименовании за пределом — не валюта таблицы).
     */
    @Test
    void xlsx_rowsAfterLimit_notText() throws Exception {
        List<List<String>> rows = new java.util.ArrayList<>();
        rows.add(List.of("Наименование", "Кол-во", "Цена"));
        for (int i = 1; i <= ReplyTableReader.MAX_ROWS; i++) rows.add(List.of("Позиция " + i, "1", "100"));
        for (int i = 0; i < 5; i++) rows.add(List.of("Кабель (с доставкой 1 500 руб.)", "1", "1 500 руб."));
        ReplyTable t = ReplyTableReader.read(xlsx(rows), ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(t.rows()).hasSize(ReplyTableReader.MAX_ROWS);
        assertThat(t.currency()).isNull();
    }

    /** Excel: валюта названа над шапкой, под бланком. */
    @Test
    void xlsx_currencyAboveHeader() throws Exception {
        ReplyTable t = ReplyTableReader.read(xlsx(List.of(
                List.of("Коммерческое предложение № 105. Цены указаны в рублях РФ."),
                List.of(),
                List.of("Наименование", "Кол-во", "Цена"),
                List.of("Кушетка смотровая", "1", "30 000"))), ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(t.currency()).isEqualTo("RUB");
    }

    /** Word 97 (.doc): валюта в абзаце вне таблицы. */
    @Test
    void doc_currencyInParagraph() throws Exception {
        byte[] doc;
        try (HWPFDocument d = new HWPFDocument(new ByteArrayInputStream(res("price-table.doc")));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.getRange().replaceText("Цена, тг", "Цена");
            // замена без исходного текста внутри: replaceText(String, String) ищет его заново, пока находит
            d.getRange().replaceText("коммерческое предложение", "КП, цены указаны в рублях");
            d.write(out);
            doc = out.toByteArray();
        }
        ReplyTable t = ReplyTableReader.read(doc, ReplyFileKind.WORD, Map.of()).orElseThrow();
        assertThat(t.columns().get(4).header()).isEqualTo("Цена");
        assertThat(t.currency()).isEqualTo("RUB");
    }

    /** «Цена, у.е.» — валюта таблицы «у.е.», а не «не названа» (иначе цены встали бы как цены рынка). */
    @Test
    void conventionalUnitsCurrency() throws Exception {
        ReplyTable t = ReplyTableReader.read(xlsx(List.of(List.of("Наименование", "Цена, у.е."), List.of("Монитор пациента", "1200"))),
                ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(t.currency()).isEqualTo("UE");
    }
}
