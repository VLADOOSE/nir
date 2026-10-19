package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.ReplyFileKind;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Готовое КП (спека 2026-10-19 §4.1): все таблицы документа с итогом «ИТОГО»; ответы поставщиков — по-прежнему одна. */
class ReplyTableReaderAllTest {

    /** КП-буклет Аксиона: таблица цен, «Наличие документации» той же ширины без своей шапки, вторая таблица цен. */
    @Test
    void allTablesWithNameAndPrice_continuationGlued_readStillPicksOne() throws Exception {
        XWPFDocument d = new XWPFDocument();
        ReplyTableReaderTest.table(d, List.of(List.of("Наименование", "Стоимость за 1 шт., тенге"),
                List.of("ЭКГ простая комплектация", "1 090 800,00"), List.of("ЭКГ с опцией", "1 317 600,00")));
        ReplyTableReaderTest.table(d, List.of(List.of("Наличие документации в РК", "Наличие документации в РК"),
                List.of("Регистрационное удостоверение", "РК-МТ-5№017777 (бессрочно)")));
        ReplyTableReaderTest.table(d, List.of(List.of("Наименование", "Стоимость за 1 шт., тенге", "Комплект поставки"),
                List.of("Дефибриллятор ДКИ-Н-11", "6 176 520,00", "1) монитор")));
        byte[] b = ReplyTableReaderTest.docx(d);

        List<ReplyTableReader.ReadTable> all = ReplyTableReader.readAll(b, ReplyFileKind.WORD, Map.of());

        assertThat(all).hasSize(2);
        assertThat(all.get(0).table().rows()).extracting(r -> r.cells().get(0)).containsExactly(
                "ЭКГ простая комплектация", "ЭКГ с опцией", "Наличие документации в РК", "Регистрационное удостоверение");
        assertThat(all.get(1).table().rows()).extracting(r -> r.cells().get(0)).containsExactly("Дефибриллятор ДКИ-Н-11");
        assertThat(all).allSatisfy(t -> assertThat(t.total()).isNull());
        assertThat(ReplyTableReader.read(b, ReplyFileKind.WORD, Map.of())).isPresent();
    }

    @Test
    void totalRowValueFromSumColumnElseLargestNumber() throws Exception {
        byte[] withSum = ReplyTableReaderTest.xlsx(List.of(
                List.of("№", "Наименование", "Кол-во", "Цена за ед., тг", "Общая сумма, тг"),
                List.of("1", "Пульсоксиметр", "3", "105 600,00", "316 800,00"),
                List.of("2", "Гигрометр", "4", "13 515,00", "54 060,00"),
                List.of("ИТОГО:", "", "7", "", "370 860,00")));
        ReplyTableReader.ReadTable t = ReplyTableReader.readAll(withSum, ReplyFileKind.EXCEL, Map.of()).get(0);
        assertThat(t.total()).isEqualByComparingTo("370860");
        assertThat(t.table().rows()).hasSize(2);

        // колонки суммы нет: итог — наибольшее число строки, а не первое («5» из «НДС 5 %»)
        byte[] noSum = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Цена, тг"),
                List.of("Центрифуга СМ-6М", "1 360 000"),
                List.of("Итого (в т.ч. НДС 5 %: 64 761,90)", "1 360 000")));
        assertThat(ReplyTableReader.readAll(noSum, ReplyFileKind.EXCEL, Map.of()).get(0).total()).isEqualByComparingTo("1360000");

        byte[] noTotal = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Цена, тг"), List.of("Ширма", "30 000")));
        assertThat(ReplyTableReader.readAll(noTotal, ReplyFileKind.EXCEL, Map.of()).get(0).total()).isNull();
    }

    @Test
    void documentLines_paragraphsThenFirstRowsOfTables() throws Exception {
        XWPFDocument d = new XWPFDocument();
        d.createParagraph().createRun().setText("Исх № 24 от 17 апреля 2026 г.");
        ReplyTableReaderTest.table(d, List.of(List.of("Наименование", "Цена, тг"), List.of("Ширма", "30 000")));
        List<String> lines = ReplyTableReader.documentLines(ReplyTableReaderTest.docx(d), ReplyFileKind.WORD);
        assertThat(lines.get(0)).isEqualTo("Исх № 24 от 17 апреля 2026 г.");
        assertThat(lines).contains("Наименование Цена, тг", "Ширма 30 000");

        XWPFDocument noTables = new XWPFDocument();
        noTables.createParagraph().createRun().setText("Дата: 24.09.2026 г.");
        assertThat(ReplyTableReader.documentLines(ReplyTableReaderTest.docx(noTables), ReplyFileKind.WORD))
                .containsExactly("Дата: 24.09.2026 г.");
    }
}
