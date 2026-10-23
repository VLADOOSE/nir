package com.vladoose.nir.pricelist;

import com.vladoose.nir.entity.PriceListColumn;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.service.pricelist.ParsedPriceList;
import com.vladoose.nir.service.pricelist.ParsedPriceRow;
import com.vladoose.nir.service.pricelist.PriceListReader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PriceListReaderTest {

    @Test
    void excelTable_twoPrices_sections_mergedReg_termsAndDates() throws Exception {
        ParsedPriceList p = PriceListReader.read("ZZ прайс.xlsx", ReplyFileKind.EXCEL, PriceListFiles.axionLikeXlsx(), Map.of());
        assertThat(p.status()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(p.columns()).extracting(PriceListColumn::index).containsExactly(0, 1);
        assertThat(p.ourColumn()).isEqualTo(1);                                       // дилерская
        assertThat(p.currency()).isEqualTo("RUB");
        assertThat(p.rows()).extracting(ParsedPriceRow::name).containsExactly("Транспортный ИВЛ А-ИВЛ-Э-03",
                "Дефибриллятор ДКИ-Н-12. Базовые функции дефибриллятора", "Дефибриллятор ДКИ-Н-12 (без сумки)",
                "Отсасыватель медицинский портативный ОМП-5/80-01");
        assertThat(p.rows()).extracting(ParsedPriceRow::section).containsExactly("Реанимация", "Реанимация", "Реанимация", "Хирургия");
        assertThat(p.rows().get(1).prices().get(0)).isEqualByComparingTo("205000");
        assertThat(p.rows().get(1).prices().get(1)).isEqualByComparingTo("150000");
        assertThat(p.rows().get(2).regText()).contains("0№024573");                  // объединённая по вертикали ячейка РУ
        assertThat(p.rows().get(3).regText()).isEqualTo("Нет РУ в Казахстане");
        assertThat(p.listDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(p.validTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(p.terms()).contains("Предоплата 100%").contains("FCA");
        assertThat(p.retail()).isFalse();
    }

    @Test
    void wordWithoutTable_textLines_retail() throws Exception {
        ParsedPriceList p = PriceListReader.read("Прайс ZZ 01_11_2025.docx", ReplyFileKind.WORD, PriceListFiles.orionLikeDocx(), Map.of());
        assertThat(p.status()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(p.rows()).hasSize(3);
        assertThat(p.rows().get(1).prices().get(0)).isEqualByComparingTo("1097000");
        assertThat(p.columns()).extracting(PriceListColumn::header).containsExactly("Цена");
        assertThat(p.ourColumn()).isZero();
        assertThat(p.retail()).isTrue();
        assertThat(p.listDate()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(p.currency()).isEqualTo("RUB");
    }

    @Test
    void garbage_failed_empty_noRows() {
        assertThat(PriceListReader.read("x.xlsx", ReplyFileKind.EXCEL, "не excel".getBytes(StandardCharsets.UTF_8), Map.of()).status())
                .isEqualTo(ReplyParseStatus.FAILED);
        assertThat(PriceListReader.read("x.png", ReplyFileKind.IMAGE, new byte[]{1, 2, 3}, Map.of()).status())
                .isEqualTo(ReplyParseStatus.NOT_PARSED);
        assertThat(PriceListReader.read("x.xlsx", ReplyFileKind.EXCEL, new byte[0], Map.of()).status())
                .isEqualTo(ReplyParseStatus.FAILED);
    }

    /** Объединённая по вертикали ячейка РУ ниже 40-й строки листа — у обеих комплектаций (задача 5, мутация 1). */
    @Test
    void mergedRegBelowHeaderScan_inBothRows() throws Exception {
        ParsedPriceList p = PriceListReader.read("длинный.xlsx", ReplyFileKind.EXCEL, PriceListFiles.longWithMergedRegXlsx(), Map.of());
        assertThat(p.rows()).hasSize(57);
        assertThat(p.rows().get(56).name()).isEqualTo("ZZ Монитор М-10 (расширенный)");
        assertThat(p.rows().get(56).regText()).isEqualTo("РК МИ (МТ)-0№099999");
    }
}
