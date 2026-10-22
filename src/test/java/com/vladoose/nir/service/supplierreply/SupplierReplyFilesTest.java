package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.service.mail.MailFile;
import com.vladoose.nir.util.KpToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SupplierReplyFilesTest {

    @Autowired SupplierReplyFiles replyFiles;

    @Test
    void excelParsed_garbageFailed_pdfPages_imageView() throws Exception {
        byte[] xlsx = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Цена, тг"), List.of("Кушетка", "95 000")));
        PreparedFile x = replyFiles.prepare("kp.xlsx", "application/octet-stream", xlsx);
        assertThat(x.kind()).isEqualTo(ReplyFileKind.EXCEL);
        assertThat(x.status()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(x.sha256()).hasSize(64);

        PreparedFile bad = replyFiles.prepare("kp.xlsx", "application/octet-stream", "не excel".getBytes());
        assertThat(bad.status()).isEqualTo(ReplyParseStatus.FAILED);
        assertThat(bad.note()).contains("не открылся");

        PreparedFile pdf = replyFiles.prepare("КП.pdf", "application/pdf", ReplyFixtures.pdfBytes(3));
        assertThat(pdf.kind()).isEqualTo(ReplyFileKind.PDF);
        assertThat(pdf.pageCount()).isEqualTo(3);

        assertThat(replyFiles.prepare("фото.jpg", "image/jpeg", new byte[]{1, 2, 3}).kind()).isEqualTo(ReplyFileKind.IMAGE);

        PreparedFile noTable = replyFiles.prepare("письмо.xlsx", "x", ReplyTableReaderTest.xlsx(List.of(List.of("Добрый день"))));
        assertThat(noTable.status()).isEqualTo(ReplyParseStatus.NO_TABLE);
    }

    /**
     * КП-буклет без таблицы (§16 «Примеры КП оператора», находка 4): цены из текста документа — Word (абзацы и ячейки
     * таблицы без колонок цены) и PDF; у разобранной таблицы — нет (там цены — строки таблицы).
     */
    @Test
    void bookletWithoutTable_textPrices_wordAndPdf() throws Exception {
        org.apache.poi.xwpf.usermodel.XWPFDocument d = new org.apache.poi.xwpf.usermodel.XWPFDocument();
        d.createParagraph().createRun().setText("Флюорограф малодозовый цифровой сканирующий ФМцс-«ПроСкан»");
        ReplyTableReaderTest.table(d, List.of(List.of("Стоимость оборудования со скидкой – 5 000 000 рублей. Стоимость оборудования "
                + "со скидкой (без АРМ врача) – 4 500 000 рублей. Ввод в эксплуатацию – 200 000 рублей.")));
        PreparedFile word = replyFiles.prepare("ПроСкан-2000.docx", "x", ReplyTableReaderTest.docx(d));
        assertThat(word.status()).isEqualTo(ReplyParseStatus.NO_TABLE);
        assertThat(word.textPrices()).extracting(x -> x.amount().stripTrailingZeros().toPlainString() + " " + x.currency())
                .containsExactly("5000000 RUB", "4500000 RUB", "200000 RUB");

        PreparedFile pdf = replyFiles.prepare("КП прессо.pdf", "application/pdf",
                ReplyFixtures.textPdf("Коммерческое предложение", "Аппарат электрический, косметический – Charmstar Super Slim – 110 000 рублей."));
        assertThat(pdf.textPrices()).extracting(x -> x.amount().stripTrailingZeros().toPlainString() + " " + x.currency())
                .containsExactly("110000 RUB");

        byte[] xlsx = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Цена, тг"), List.of("Кушетка", "95 000")));
        assertThat(replyFiles.prepare("kp.xlsx", "x", xlsx).textPrices()).isEmpty();
    }

    /**
     * Цены в тексте PDF — с первых {@link ReplyPdfPages#MAX_PAGES} страниц (столько же показывает экран сверки): каталог
     * на сотни страниц не держит единственный поток приёма почты zakup@.
     */
    @Test
    void pdfTextPrices_firstPagesOnly() throws Exception {
        String[] text = new String[ReplyPdfPages.MAX_PAGES + 1];
        for (int i = 0; i < text.length; i++) text[i] = "Страница " + (i + 1);
        text[ReplyPdfPages.MAX_PAGES] = "Стоимость 110 000 рублей";
        assertThat(SupplierReplyFiles.pdfLines(ReplyFixtures.textPdfPages(text))).noneMatch(l -> l.contains("110 000"));
        text[0] = "Стоимость 120 000 рублей";
        assertThat(SupplierReplyFiles.pdfLines(ReplyFixtures.textPdfPages(text))).anyMatch(l -> l.contains("120 000"));
    }

    @Autowired HeaderDictionary dictionary;
    @Autowired ReplyPdfPages pages;

    /** Прайс-лист на десятки тысяч строк не разбирается (память прода); имя файла — без пути и U+0000. */
    @Test
    void bigOoxmlIsNotParsed_fileNameCleaned() throws Exception {
        byte[] xlsx = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Цена, тг"), List.of("Кушетка", "95 000")));
        assertThat(new SupplierReplyFiles(dictionary, pages, 100).prepare("kp.xlsx", "x", xlsx).status())
                .isEqualTo(ReplyParseStatus.NOT_PARSED);
        assertThat(OoxmlSlim.forParsing(xlsx, 100)).isNull();
        assertThat(OoxmlSlim.forParsing(xlsx, SupplierReplyFiles.PARTS_MAX)).isNotNull();
        assertThat(SupplierReplyFiles.fileName("C:\\Users\\x\\КП\0.xlsx")).isEqualTo("КП.xlsx");
        assertThat(SupplierReplyFiles.fileName("  ")).isEqualTo("файл");
    }

    @Test
    void prepareFor_onlyLettersWithKpToken_tooLargeWithoutBytes() {
        MailFile big = new MailFile("большой.pdf", "application/pdf", null, null);
        assertThat(replyFiles.prepareFor(mail().subject("Прайс").files(big).build())).isEmpty();
        List<PreparedFile> p = replyFiles.prepareFor(mail().subject("Re: " + KpToken.subjectToken(5L)).files(big).build());
        assertThat(p).singleElement().satisfies(f -> {
            assertThat(f.status()).isEqualTo(ReplyParseStatus.TOO_LARGE);
            assertThat(f.bytes()).isNull();
        });
    }
}
