package com.vladoose.nir.service.readyoffer;

import com.vladoose.nir.entity.ClientOfferItemKind;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.service.supplierreply.ReplyFixtures;
import com.vladoose.nir.service.supplierreply.ReplyPdfPages;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Файл готового КП → строки, итог, номер и дата (спека 2026-10-19 §4). Словарь заголовков — пустой: правила. */
class ReadyOfferReaderTest {

    final ReadyOfferReader reader = new ReadyOfferReader(null, new ReplyPdfPages());

    ReadyOfferReader.Result read(String name, byte[] b) {
        return reader.read(name, null, b, "KZT", Map.of());
    }

    @Test
    void fatherWordKp_parsedWithTotalAndDate() throws Exception {
        byte[] kp = ReadyOfferFixtures.fatherKp("24.09.2026", List.of(
                new String[]{"Пульсоксиметр QMP-PO70", "3", "105 600,00", "316 800,00"},
                new String[]{"Гигрометр ВИТ-2", "4", "13 515,00", "54 060,00"}), "370 860,00");
        ReadyOfferReader.Result r = read("КП West-Med 24.09.2026.docx", kp);
        assertThat(r.kind()).isEqualTo(ReplyFileKind.WORD);
        assertThat(r.status()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(r.lines()).extracting(OfferFileLine::name).containsExactly("Пульсоксиметр QMP-PO70", "Гигрометр ВИТ-2");
        assertThat(r.fileTotal()).isEqualByComparingTo("370860");
        assertThat(r.header()).isEqualTo(new OfferHeader(null, LocalDate.of(2026, 9, 24)));
        assertThat(r.tables()).hasSize(1);
    }

    @Test
    void mergedFloorRows_sectionsNotPricedLines() throws Exception {   // КП «ОЗОН»: «1 этаж» во всю ширину
        XWPFDocument d = new XWPFDocument();
        XWPFTable t = ReadyOfferFixtures.table(d, List.of(ReadyOfferFixtures.FATHER_HEADER,
                List.of("1", "Консоль МК-НД-800", "шт", "2", "2 386 800,00", "В том числе НДС 16%", "4 773 600,00"),
                List.of("2", "Консоль МК-ПГ-700", "шт", "1", "1 899 240,00", "В том числе НДС 16%", "1 899 240,00")));
        ReadyOfferFixtures.mergedRow(t, 1, "1 этаж", 7);
        ReadyOfferFixtures.mergedRow(t, 3, "2 этаж", 7);
        ReadyOfferReader.Result r = read("КП ОЗОН.docx", ReadyOfferFixtures.bytes(d));
        assertThat(r.lines()).extracting(OfferFileLine::kind).containsExactly(ClientOfferItemKind.SECTION, ClientOfferItemKind.ITEM,
                ClientOfferItemKind.SECTION, ClientOfferItemKind.ITEM);
        assertThat(r.lines()).extracting(OfferFileLine::name).containsExactly("1 этаж", "Консоль МК-НД-800", "2 этаж", "Консоль МК-ПГ-700");
        BigDecimal sum = r.lines().stream().filter(l -> l.price() != null)
                .map(l -> l.price().multiply(l.quantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("6672840");
    }

    @Test
    void rublesOnKzMarket_noPricesForeignCurrencyNoted() throws Exception {
        byte[] x = ReadyOfferFixtures.xlsx(List.of(List.of("Наименование", "Кол-во", "Цена, руб."), List.of("Консоль", "1", "940 000")));
        ReadyOfferReader.Result r = read("КП.xlsx", x);
        assertThat(r.lines()).singleElement().satisfies(l -> assertThat(l.price()).isNull());
        assertThat(r.foreignCurrency()).isEqualTo("RUB");
    }

    @Test
    void pdfPicturesAndBrokenFiles() throws Exception {
        ReadyOfferReader.Result pdf = read("КП № 481 от 07.10.2026 – Клиника.pdf", ReplyFixtures.pdfBytes(2));
        assertThat(pdf.kind()).isEqualTo(ReplyFileKind.PDF);
        assertThat(pdf.status()).isEqualTo(ReplyParseStatus.NOT_PARSED);
        assertThat(pdf.pages()).isEqualTo(2);
        assertThat(pdf.lines()).isEmpty();
        assertThat(pdf.header()).isEqualTo(new OfferHeader(481, LocalDate.of(2026, 10, 7)));   // из имени файла

        assertThat(read("КП.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, 1}).kind()).isEqualTo(ReplyFileKind.IMAGE);

        ReadyOfferReader.Result broken = read("КП.docx", "PK\u0003\u0004 не архив".getBytes());
        assertThat(broken.status()).isEqualTo(ReplyParseStatus.FAILED);
        assertThat(broken.note()).contains("не открылся");
    }

    @Test
    void word97Fixture_parsed() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/supplier-reply/price-table.doc")) {
            ReadyOfferReader.Result r = read("price-table.doc", in.readAllBytes());
            assertThat(r.status()).isEqualTo(ReplyParseStatus.PARSED);
            assertThat(r.lines()).isNotEmpty().allSatisfy(l -> assertThat(l.kind()).isEqualTo(ClientOfferItemKind.ITEM));
        }
    }
}
