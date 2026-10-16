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
