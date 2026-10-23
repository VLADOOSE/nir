package com.vladoose.nir.pricelist;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.pricelist.ParsedPriceList;
import com.vladoose.nir.service.pricelist.ParsedPriceRow;
import com.vladoose.nir.service.pricelist.PdfTableReader;
import com.vladoose.nir.service.pricelist.PriceListReader;
import com.vladoose.nir.service.supplierreply.ReplyFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PdfTableReaderTest {

    @Test
    void grid_headerRoles_mergedCells_secondPageOwnGrid_outsideText() throws Exception {
        PdfTableReader.Result r = PdfTableReader.read(PriceListFiles.axionLikePdf(), Map.of()).orElseThrow();
        ReplyTable t = r.table();
        assertThat(t.columns()).extracting(ReplyTable.Column::role).containsExactly(LineField.NUMBER, LineField.IGNORE, LineField.NAME,
                LineField.PRICE, LineField.PRICE, LineField.REG);
        assertThat(t.columns().get(3).header()).isEqualTo("Оптовая цена, RUB");
        assertThat(t.rows()).hasSize(5);                                            // раздел + 3 строки + строка второй страницы
        assertThat(t.rows().get(0).cells().get(2)).isEqualTo("Реанимация");
        assertThat(t.rows().get(2).cells().get(2)).isEqualTo("Дефибриллятор ДКИ-Н-12. Базовые функции");
        assertThat(t.rows().get(2).cells().get(4)).isEqualTo("150 000");
        // объединённая на две строки ячейка РУ — у обеих строк; перенос после дефиса — без пробела
        assertThat(t.rows().get(2).cells().get(5)).isEqualTo("РУ РК МИ (МТ)-0№024573 (бессрочно)");
        assertThat(t.rows().get(3).cells().get(5)).isEqualTo("РУ РК МИ (МТ)-0№024573 (бессрочно)");
        assertThat(t.rows().get(4).cells().get(2)).isEqualTo("Отсасыватель портативный ОМП-5/80-01");   // своя сетка страницы 2
        assertThat(t.currency()).isEqualTo("RUB");
        assertThat(r.outside()).contains("Прайс-лист ООО «ZZ Завод»", "• Предоплата 100%;");
    }

    @Test
    void reader_pdfGrid_toPriceList() throws Exception {
        ParsedPriceList p = PriceListReader.read("ZZ прайс (01.09.2026).pdf", ReplyFileKind.PDF, PriceListFiles.axionLikePdf(), Map.of());
        assertThat(p.status()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(p.rows()).extracting(ParsedPriceRow::section).containsExactly("Реанимация", "Реанимация", "Реанимация", "Реанимация");
        assertThat(p.ourColumn()).isEqualTo(1);
        assertThat(p.rows().get(3).prices().get(1)).isEqualByComparingTo("60100");
        assertThat(p.listDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(p.validTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(p.terms()).contains("Предоплата 100%");
    }

    /** Review Focus 5: PDF без сетки — строки текста; без текста (скан) — «строки не найдены», без падения. */
    @Test
    void noGrid_textLines_scan_noRows() throws Exception {
        ParsedPriceList text = PriceListReader.read("прайс.pdf", ReplyFileKind.PDF,
                ReplyFixtures.textPdf("Стерилизатор озоновый СК-16 …………… 957 000 руб.", "Предоплата 100%"), Map.of());
        assertThat(text.rows()).hasSize(1);
        assertThat(text.rows().get(0).prices().get(0)).isEqualByComparingTo("957000");
        ParsedPriceList scan = PriceListReader.read("скан.pdf", ReplyFileKind.PDF, ReplyFixtures.pdfBytes(1), Map.of());
        assertThat(scan.status()).isEqualTo(ReplyParseStatus.NO_TABLE);
        assertThat(scan.note()).isEqualTo(PriceListReader.NO_ROWS);
        assertThat(PdfTableReader.read(ReplyFixtures.textPdf("просто текст"), Map.of())).isEmpty();
    }
}
