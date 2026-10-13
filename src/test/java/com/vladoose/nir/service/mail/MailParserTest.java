package com.vladoose.nir.service.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.vladoose.nir.service.mail.TestMimes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class MailParserTest {

    @Test
    void plainLetter_cyrillicSender_subjectMessageIdText() throws Exception {
        MimeMessage m = roundTrip(plain("Иван Петров <ivan@medtech.kz>", "Re: [КП-534] Запрос", "Цена 100 тенге"));

        ParsedMail p = MailParser.parse(m, 7);

        assertThat(p.uid()).isEqualTo(7);
        assertThat(p.from()).isEqualTo("Иван Петров <ivan@medtech.kz>");
        assertThat(p.fromAddress()).isEqualTo("ivan@medtech.kz");
        assertThat(p.subject()).isEqualTo("Re: [КП-534] Запрос");
        assertThat(p.messageId()).startsWith("<").endsWith(">");
        assertThat(p.text()).contains("Цена 100 тенге");
        assertThat(p.html()).isEmpty();
        assertThat(p.attachmentNames()).isEmpty();
        assertThat(p.bounce()).isNull();
        assertThat(p.contentType()).startsWith("text/plain");
    }

    @Test
    void htmlOnly_bodyWithoutStyleBlocks() throws Exception {
        MimeMessage m = roundTrip(html("s@x.kz", "Цена",
                "<html><head><style>p{color:red}</style></head><body><p>Добрый день!</p><p>Цена 5 000 тг</p></body></html>"));

        ParsedMail p = MailParser.parse(m, 1);

        assertThat(p.text()).isEmpty();
        assertThat(p.body()).contains("Добрый день!").contains("Цена 5 000 тг").doesNotContain("color");
    }

    @Test
    void rawBody_htmlOnlyGivesRawHtml_plainGivesText() throws Exception {
        String source = "<p>Цена <b>1 500 000</b> тг</p>";
        ParsedMail htmlOnly = MailParser.parse(roundTrip(html("s@x.kz", "Re: [КП-534] Запрос", source)), 1);
        ParsedMail plainOnly = MailParser.parse(
                roundTrip(plain("s@x.kz", "Re: [КП-534] Запрос", "Цена 1 500 000 тг")), 1);

        assertThat(htmlOnly.rawBody()).isEqualTo(source);
        assertThat(plainOnly.rawBody()).isEqualTo("Цена 1 500 000 тг");
    }

    @Test
    void nestedAlternativeWithEncodedExcel_textExcelAndName() throws Exception {
        MimeMultipart alt = new MimeMultipart("alternative");
        MimeBodyPart t = new MimeBodyPart();
        t.setText("Прошу выставить КП по списку", "UTF-8");
        MimeBodyPart h = new MimeBodyPart();
        h.setContent("<p>Прошу выставить КП по списку</p>", "text/html; charset=UTF-8");
        alt.addBodyPart(t);
        alt.addBodyPart(h);
        MimeBodyPart altPart = new MimeBodyPart();
        altPart.setContent(alt);
        MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new DataHandler(new ByteArrayDataSource(xlsxBytes(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")));
        file.setFileName(MimeUtility.encodeText("Список ТХ.xlsx", "UTF-8", "B"));
        file.setDisposition(MimeBodyPart.ATTACHMENT);
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(altPart);
        mixed.addBodyPart(file);
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("clinic@x.kz"));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress("zakup@westmed.kz"));
        m.setSubject("Заявка", "UTF-8");
        m.setContent(mixed);
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.text()).contains("Прошу выставить КП");
        assertThat(p.excelBytes()).isNotNull();
        assertThat(p.excelName()).isEqualTo("Список ТХ.xlsx");
        assertThat(p.attachmentNames()).containsExactly("Список ТХ.xlsx");
    }

    @Test
    void inlineSignatureImage_isNotAttachment_pdfIs() throws Exception {
        MimeMultipart related = new MimeMultipart("related");
        MimeBodyPart h = new MimeBodyPart();
        h.setContent("<p>Цена в файле</p><img src=\"cid:logo1\">", "text/html; charset=UTF-8");
        related.addBodyPart(h);
        MimeBodyPart img = new MimeBodyPart();
        img.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{1, 2, 3}, "image/png")));
        img.setContentID("<logo1>");
        img.setDisposition(MimeBodyPart.INLINE);
        img.setFileName("image001.png");
        related.addBodyPart(img);
        MimeBodyPart relPart = new MimeBodyPart();
        relPart.setContent(related);
        MimeBodyPart pdf = new MimeBodyPart();
        pdf.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{4, 5}, "application/pdf")));
        pdf.setFileName(MimeUtility.encodeText("КП.pdf", "UTF-8", "B"));
        pdf.setDisposition(MimeBodyPart.ATTACHMENT);
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(relPart);
        mixed.addBodyPart(pdf);
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("s@x.kz"));
        m.setSubject("КП", "UTF-8");
        m.setContent(mixed);
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.attachmentNames()).containsExactly("КП.pdf");
        assertThat(p.body()).contains("Цена в файле");
    }

    @Test
    void textPartsAroundAttachment_joinedWithLineBreak() throws Exception {
        // так пишет Apple Mail, когда файл вставлен посреди текста: текст, вложение, текст. Встык части дали бы
        // «Количество 21 500 000 тг» — для разбора цены одно число
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart first = new MimeBodyPart();
        first.setText("Количество 2", "UTF-8");
        MimeBodyPart pdf = new MimeBodyPart();
        pdf.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{4, 5}, "application/pdf")));
        pdf.setFileName(MimeUtility.encodeText("КП.pdf", "UTF-8", "B"));
        pdf.setDisposition(MimeBodyPart.ATTACHMENT);
        MimeBodyPart second = new MimeBodyPart();
        second.setText("1 500 000 тг за единицу", "UTF-8");
        mixed.addBodyPart(first);
        mixed.addBodyPart(pdf);
        mixed.addBodyPart(second);
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("s@x.kz"));
        m.setSubject("Re: [КП-534] Запрос", "UTF-8");
        m.setContent(mixed);
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.text()).contains("Количество 2\n1 500 000 тг").doesNotContain("21 500 000");
        assertThat(p.attachmentNames()).containsExactly("КП.pdf");
    }

    @Test
    void dsnWithOriginalMessage_bounceFields_andOriginalTextNotMerged() throws Exception {
        MimeMessage m = roundTrip(dsn("[КП-534] Запрос КП", "sales@medtech.kz",
                "550 5.1.1 <sales@medtech.kz>:\r\n    Recipient address rejected: User unknown"));

        ParsedMail p = MailParser.parse(m, 3);

        assertThat(p.contentType()).startsWith("multipart/report").contains("delivery-status");
        assertThat(p.fromAddress()).isEqualTo("mailer-daemon@corp.mail.ru");
        assertThat(p.bounce()).isNotNull();
        assertThat(p.bounce().finalRecipient()).isEqualTo("sales@medtech.kz");
        assertThat(p.bounce().status()).isEqualTo("5.1.1");
        assertThat(p.bounce().diagnostic()).contains("550 5.1.1").contains("User unknown");
        assertThat(p.bounce().originalSubject()).isEqualTo("[КП-534] Запрос КП");
        assertThat(p.bounce().action()).isEqualTo("failed");
        assertThat(p.text()).contains("could not be delivered").doesNotContain("Просим коммерческое");
    }

    /** Отчёт об отложенной доставке: Action читается нижним регистром — RFC 3464 регистр значения не задаёт. */
    @Test
    void dsnDelayed_actionLowercased() throws Exception {
        String fields = "Reporting-MTA: dns; mx.mail.ru\r\n\r\n"
                + "Final-Recipient: rfc822; sales@x.kz\r\n"
                + "Action: Delayed\r\n"
                + "Status: 4.4.1\r\n"
                + "Diagnostic-Code: smtp; 421 4.4.1 Connection timed out\r\n";
        MimeBodyPart status = new MimeBodyPart();
        status.setDataHandler(new DataHandler(new ByteArrayDataSource(fields.getBytes(StandardCharsets.US_ASCII),
                "message/delivery-status")));
        status.setHeader("Content-Type", "message/delivery-status");
        MimeMultipart report = new MimeMultipart("report; report-type=delivery-status");
        report.addBodyPart(status);
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("MAILER-DAEMON@corp.mail.ru"));
        m.setSubject("Delayed Mail (still being retried)");
        m.setContent(report);
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.bounce()).isNotNull();
        assertThat(p.bounce().action()).isEqualTo("delayed");
        assertThat(p.bounce().status()).isEqualTo("4.4.1");
    }

    @Test
    void dsnWithHeadersOnly_decodesEncodedSubject() throws Exception {
        ParsedMail p = MailParser.parse(roundTrip(dsnHeadersOnly("[КП-77] Запрос коммерческого предложения")), 1);

        assertThat(p.bounce()).isNotNull();
        assertThat(p.bounce().originalSubject()).isEqualTo("[КП-77] Запрос коммерческого предложения");
    }

    @Test
    void autoReplyHeaders_lowercased() throws Exception {
        MimeMessage m = plain("s@x.kz", "Автоответ", "Я в отпуске");
        m.setHeader("Auto-Submitted", "Auto-Replied");
        m.setHeader("X-Autoreply", "yes");
        m.setHeader("Precedence", "Bulk");
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.autoHeaders()).containsEntry("auto-submitted", "auto-replied")
                .containsEntry("x-autoreply", "yes").containsEntry("precedence", "bulk");
    }

    @Test
    void unknownCharset_doesNotThrow_readsBytes() throws Exception {
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("s@x.kz"));
        m.setSubject("Кодировка", "UTF-8");
        m.setDataHandler(new DataHandler(new ByteArrayDataSource("Привет".getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=x-unknown-777")));
        m.setHeader("Content-Type", "text/plain; charset=x-unknown-777");
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.text()).contains("Привет");
    }

    @Test
    void addressPart_cases() {
        assertThat(MailParser.addressPart("Иван <Ivan@X.kz>")).isEqualTo("ivan@x.kz");
        assertThat(MailParser.addressPart("a@b.kz")).isEqualTo("a@b.kz");
        assertThat(MailParser.addressPart(null)).isEmpty();
    }

    /**
     * U+0000 (RFC 2047 «=?UTF-8?B?…AA…?=», нулевой байт в теле или заголовке) PostgreSQL в text не хранит (SQLSTATE 22021):
     * письмо не записалось бы ни целиком, ни коротко и стопорило бы ящик на каждом проходе. Разбор вычищает символ
     * из каждой строки письма — тема, отправитель, Message-ID, текст, HTML, имена вложений, заголовки автоответа.
     */
    @Test
    void nulCharacter_strippedFromEveryParsedString() throws Exception {
        MimeMultipart alt = new MimeMultipart("alternative");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Цена\0 100 тенге", "UTF-8");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>Цена\0 100 тенге</p>", "text/html; charset=UTF-8");
        alt.addBodyPart(text);
        alt.addBodyPart(html);
        MimeBodyPart altPart = new MimeBodyPart();
        altPart.setContent(alt);
        MimeBodyPart excel = new MimeBodyPart();
        excel.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{1, 2}, "application/octet-stream")));
        excel.setHeader("Content-Disposition", "attachment; filename=\"" + encodedWord("прайс\0.xlsx") + "\"");
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(altPart);
        mixed.addBodyPart(excel);
        MimeMessage m = new MimeMessage((Session) null);
        m.setContent(mixed);
        m.saveChanges();
        m.setHeader("From", encodedWord("Иван\0 Петров") + " <ivan@x.kz>");
        m.setHeader("Subject", encodedWord("Re: [КП-5]\0 Запрос"));
        m.setHeader("Message-ID", "<nul\0id@x.kz>");                 // после saveChanges — иначе перезапишется
        m.setHeader("X-Autoreply", "yes\0");

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(allStrings(p)).isNotEmpty().noneMatch(s -> s.indexOf('\0') >= 0);
        assertThat(p.subject()).isEqualTo("Re: [КП-5] Запрос");
        assertThat(p.from()).isEqualTo("Иван Петров <ivan@x.kz>");
        assertThat(p.messageId()).isEqualTo("<nulid@x.kz>");
        assertThat(p.text()).contains("Цена 100 тенге");
        assertThat(p.html()).contains("Цена 100 тенге");
        assertThat(p.excelName()).isEqualTo("прайс.xlsx");
        assertThat(p.autoHeaders()).containsEntry("x-autoreply", "yes");
    }

    /** Возврат с U+0000 в теме исходного письма, адресате и диагностике — поля возврата тоже без него. */
    @Test
    void nulCharacter_strippedFromBounceFields() throws Exception {
        ParsedMail p = MailParser.parse(roundTrip(dsn("[КП-9]\0 Запрос КП", "b\0@x.kz", "550\0 5.1.1 User unknown")), 1);

        assertThat(allStrings(p)).noneMatch(s -> s.indexOf('\0') >= 0);
        assertThat(p.bounce().originalSubject()).isEqualTo("[КП-9] Запрос КП");
        assertThat(p.bounce().finalRecipient()).isEqualTo("b@x.kz");
        assertThat(p.bounce().diagnostic()).isEqualTo("550 5.1.1 User unknown");
    }

    /**
     * Конверт вложенного письма без темы (NIL у IMAP-сервера) — тема берётся из потока части, как до перехода на конверт.
     * Часть подменена: её содержимое — письмо без темы, а поток — исходное письмо с темой.
     */
    @Test
    void bounce_nestedEnvelopeWithoutSubject_fallsBackToPartStream() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        plain("zakup@westmed.kz", "[КП-7] Запрос КП", "Просим коммерческое предложение.").writeTo(raw);
        MimeMessage withoutSubject = new MimeMessage((Session) null);
        withoutSubject.setText("x");
        MimeBodyPart original = new MimeBodyPart() {
            @Override public Object getContent() { return withoutSubject; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(raw.toByteArray()); }
        };
        original.setHeader("Content-Type", "message/rfc822");
        MimeBodyPart human = new MimeBodyPart();
        human.setText("Your message could not be delivered.", "UTF-8");
        MimeMultipart report = new MimeMultipart("report; report-type=delivery-status");
        report.addBodyPart(human);
        report.addBodyPart(original);
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("MAILER-DAEMON@corp.mail.ru"));
        m.setSubject("Undelivered Mail Returned to Sender");
        m.setContent(report);
        m.setHeader("Content-Type", report.getContentType());       // без saveChanges: подменённая часть — как есть

        ParsedMail p = MailParser.parse(m, 1);

        assertThat(p.bounce()).isNotNull();
        assertThat(p.bounce().originalSubject()).isEqualTo("[КП-7] Запрос КП");
    }

    @Test
    void recipients_toAndCc_lowercasedWithoutRepeats_groupsSkipped() throws Exception {
        MimeMessage m = plain("Иван <ivan@clinic.kz>", "Запрос", "Текст");
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("Info@WestMed.kz, buyer@clinic.kz"));
        m.setRecipients(Message.RecipientType.CC, InternetAddress.parse("buyer@clinic.kz, undisclosed-recipients:;"));
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.recipients()).containsExactly("info@westmed.kz", "buyer@clinic.kz");
    }

    @Test
    void listHeaders_lowercased() throws Exception {
        MimeMessage m = plain("news@shop.kz", "Акция", "Скидки");
        m.setHeader("List-Unsubscribe", "<mailto:Unsub@Shop.kz>");
        m.setHeader("List-Id", "News <news.shop.kz>");
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.autoHeaders()).containsEntry("list-unsubscribe", "<mailto:unsub@shop.kz>").containsKey("list-id");
    }

    /** Ящик info@: файлы с байтами до предела; больше предела — имя без байтов и без размера. */
    @Test
    void files_collectedUnderLimit_overLimitWithoutBytes() throws Exception {
        MimeMessage m = roundTrip(withAttachments("Клиент <c@clinic.kz>", "Файлы", "Во вложении",
                "ТЗ.pdf", new byte[]{1, 2, 3}, "видео.mp4", new byte[50]));

        ParsedMail p = MailParser.parse(m, 1, 10);

        assertThat(p.files()).extracting(MailFile::name).containsExactly("ТЗ.pdf", "видео.mp4");
        assertThat(p.files().get(0).content()).isEqualTo(new byte[]{1, 2, 3});
        assertThat(p.files().get(0).size()).isEqualTo(3L);
        assertThat(p.files().get(0).mimeType()).isEqualTo("application/octet-stream");
        assertThat(p.files().get(1).stored()).isFalse();
        assertThat(p.files().get(1).size()).isNull();
    }

    /** Ящик zakup@ (без предела) байтов файлов не собирает; Excel — те же байты, что excelBytes, без второго чтения. */
    @Test
    void files_onlyWithLimit_excelBytesReused() throws Exception {
        MimeMessage m = roundTrip(withAttachments("s@x.kz", "Смета", "Текст", "смета.xlsx", xlsxBytes()));

        assertThat(MailParser.parse(m, 1).files()).isEmpty();
        ParsedMail p = MailParser.parse(m, 1, 1_000_000);
        assertThat(p.files()).singleElement().satisfies(f -> {
            assertThat(f.name()).isEqualTo("смета.xlsx");
            assertThat(f.content()).isEqualTo(p.excelBytes());
        });
    }

    /**
     * Фото, вставленное в текст письма (iPhone, Gmail «в текст», вставка в Outlook), — тоже inline + Content-ID, но это
     * не подпись: файлом, иначе от клиента не остаётся и следа. Подпись — мелкая картинка, её по-прежнему пропускаем.
     * У zakup@ (без предела файлов) список вложений — как был.
     */
    @Test
    void files_photoInsertedIntoText_isFile_smallSignatureIsNot() throws Exception {
        byte[] photo = new byte[40 * 1024];
        java.util.Arrays.fill(photo, (byte) 7);
        MimeMultipart related = new MimeMultipart("related");
        MimeBodyPart h = new MimeBodyPart();
        h.setContent("<p>Фото шильдика</p><img src=\"cid:p1\"><img src=\"cid:p2\"><img src=\"cid:logo1\">",
                "text/html; charset=UTF-8");
        related.addBodyPart(h);
        related.addBodyPart(inlineImage(photo, "image/jpeg", "<p1>", "IMG_0042.jpg"));
        related.addBodyPart(inlineImage(photo, "image/jpeg", "<p2>", null));
        related.addBodyPart(inlineImage(new byte[1024], "image/png", "<logo1>", "image001.png"));
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("c@clinic.kz"));
        m.setSubject("Поломка", "UTF-8");
        m.setContent(related);
        m.saveChanges();

        ParsedMail info = MailParser.parse(roundTrip(m), 1, 1_000_000);
        ParsedMail zakup = MailParser.parse(roundTrip(m), 1);

        assertThat(info.files()).extracting(MailFile::name, MailFile::size)
                .containsExactly(tuple("IMG_0042.jpg", 40L * 1024), tuple("фото.jpg", 40L * 1024));
        assertThat(info.files().get(0).content()).isEqualTo(photo);
        assertThat(zakup.attachmentNames()).isEmpty();
        assertThat(zakup.files()).isEmpty();
    }

    private static MimeBodyPart inlineImage(byte[] bytes, String mime, String cid, String name) throws Exception {
        MimeBodyPart img = new MimeBodyPart();
        img.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, mime)));
        img.setContentID(cid);
        img.setDisposition(MimeBodyPart.INLINE);
        if (name != null) img.setFileName(name);
        return img;
    }

    /** Картинка подписи (inline + Content-ID) — не файл письма. */
    @Test
    void files_inlineSignatureImageSkipped() throws Exception {
        MimeMultipart related = new MimeMultipart("related");
        MimeBodyPart h = new MimeBodyPart();
        h.setContent("<p>Цена в файле</p><img src=\"cid:logo1\">", "text/html; charset=UTF-8");
        related.addBodyPart(h);
        MimeBodyPart img = new MimeBodyPart();
        img.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{1, 2, 3}, "image/png")));
        img.setContentID("<logo1>");
        img.setDisposition(MimeBodyPart.INLINE);
        img.setFileName("image001.png");
        related.addBodyPart(img);
        MimeBodyPart relPart = new MimeBodyPart();
        relPart.setContent(related);
        MimeBodyPart pdf = new MimeBodyPart();
        pdf.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{4, 5}, "application/pdf")));
        pdf.setFileName("КП.pdf");
        pdf.setDisposition(MimeBodyPart.ATTACHMENT);
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(relPart);
        mixed.addBodyPart(pdf);
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("s@x.kz"));
        m.setSubject("КП", "UTF-8");
        m.setContent(mixed);
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1, 1000);

        assertThat(p.files()).extracting(MailFile::name, MailFile::mimeType).containsExactly(tuple("КП.pdf", "application/pdf"));
    }

    /** U+0000 вычищается и из имён файлов. */
    @Test
    void nulCharacter_strippedFromFileNames() throws Exception {
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Текст", "UTF-8");
        MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{1}, "application/pdf")));
        file.setHeader("Content-Disposition", "attachment; filename=\"" + encodedWord("ТЗ\0.pdf") + "\"");
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(text);
        mixed.addBodyPart(file);
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("c@clinic.kz"));
        m.setContent(mixed);
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1, 1000);

        assertThat(allStrings(p)).noneMatch(s -> s.indexOf('\0') >= 0);
        assertThat(p.files()).extracting(MailFile::name).containsExactly("ТЗ.pdf");
    }

    /** Все строки записи — по её компонентам, вложенные записи тоже: новое поле попадёт в проверку само. */
    static List<String> allStrings(Object record) throws Exception {
        List<String> out = new ArrayList<>();
        for (RecordComponent c : record.getClass().getRecordComponents()) {
            Object v = c.getAccessor().invoke(record);
            if (v instanceof String s) out.add(s);
            else if (v instanceof Collection<?> col) col.forEach(o -> out.add(String.valueOf(o)));
            else if (v instanceof Map<?, ?> map) map.forEach((k, val) -> { out.add(String.valueOf(k)); out.add(String.valueOf(val)); });
            else if (v != null && v.getClass().isRecord()) out.addAll(allStrings(v));
        }
        return out;
    }
}
