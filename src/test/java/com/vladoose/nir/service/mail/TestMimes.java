package com.vladoose.nir.service.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

/** Письма в MIME для тестов разбора и приёма: как их собирают настоящие почтовые клиенты и серверы. */
public final class TestMimes {

    private TestMimes() {}

    /**
     * Слово RFC 2047 (B, UTF-8) — так почтовые программы передают кириллицу в заголовках; внутри может оказаться что
     * угодно, включая U+0000. Ставится в заголовок как есть: setHeader(«Subject», encodedWord(…)).
     */
    public static String encodedWord(String s) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    /** «Имя <адрес>» или «адрес» → InternetAddress с UTF-8 именем. */
    public static InternetAddress addr(String s) throws Exception {
        int lt = s.indexOf('<');
        if (lt < 0) return new InternetAddress(s.trim());
        return new InternetAddress(s.substring(lt + 1, s.indexOf('>')).trim(), s.substring(0, lt).trim(), "UTF-8");
    }

    private static MimeMessage base(String from, String subject) throws Exception {
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(addr(from));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress("zakup@westmed.kz"));
        m.setSubject(subject, "UTF-8");
        m.setSentDate(new Date());
        return m;
    }

    public static MimeMessage plain(String from, String subject, String text) throws Exception {
        MimeMessage m = base(from, subject);
        m.setText(text, "UTF-8");
        m.saveChanges();
        return m;
    }

    public static MimeMessage html(String from, String subject, String html) throws Exception {
        MimeMessage m = base(from, subject);
        m.setContent(html, "text/html; charset=UTF-8");
        m.saveChanges();
        return m;
    }

    /** Текст + вложения: имя файла → байты. Имя кодируется RFC 2047, как у почтовых клиентов. */
    public static MimeMessage withAttachments(String from, String subject, String text, Object... nameAndBytes) throws Exception {
        MimeMessage m = base(from, subject);
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        body.setText(text, "UTF-8");
        mixed.addBodyPart(body);
        for (int i = 0; i < nameAndBytes.length; i += 2) {
            MimeBodyPart file = new MimeBodyPart();
            file.setDataHandler(new DataHandler(new ByteArrayDataSource((byte[]) nameAndBytes[i + 1], "application/octet-stream")));
            file.setFileName(MimeUtility.encodeText((String) nameAndBytes[i], "UTF-8", "B"));
            file.setDisposition(MimeBodyPart.ATTACHMENT);
            mixed.addBodyPart(file);
        }
        m.setContent(mixed);
        m.saveChanges();
        return m;
    }

    /** Возврат почтового сервера (RFC 3464): пояснение + message/delivery-status + исходное письмо message/rfc822. */
    public static MimeMessage dsn(String originalSubject, String recipient, String diagnostic) throws Exception {
        MimeMessage m = base("Mail Delivery System <MAILER-DAEMON@corp.mail.ru>", "Undelivered Mail Returned to Sender");
        MimeMultipart report = new MimeMultipart("report; report-type=delivery-status");
        report.addBodyPart(human());
        report.addBodyPart(deliveryStatus(recipient, diagnostic));

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        plain("zakup@westmed.kz", originalSubject, "Здравствуйте! Просим коммерческое предложение.").writeTo(raw);
        MimeBodyPart original = new MimeBodyPart();
        original.setDataHandler(new DataHandler(new ByteArrayDataSource(raw.toByteArray(), "message/rfc822")));
        original.setHeader("Content-Type", "message/rfc822");
        report.addBodyPart(original);

        m.setContent(report);
        m.saveChanges();
        return m;
    }

    /** Возврат, где вместо исходного письма — только его заголовки (text/rfc822-headers), тема закодирована RFC 2047. */
    public static MimeMessage dsnHeadersOnly(String originalSubject) throws Exception {
        MimeMessage m = base("postmaster@mx.example.kz", "Delivery Status Notification (Failure)");
        MimeMultipart report = new MimeMultipart("report; report-type=delivery-status");
        report.addBodyPart(human());
        report.addBodyPart(deliveryStatus("sales@x.kz", "550 5.1.1 User unknown"));
        String headers = "From: zakup@westmed.kz\r\nTo: sales@x.kz\r\nSubject: "
                + MimeUtility.encodeText(originalSubject, "UTF-8", "B") + "\r\n\r\n";
        MimeBodyPart h = new MimeBodyPart();
        h.setDataHandler(new DataHandler(new ByteArrayDataSource(headers.getBytes(StandardCharsets.US_ASCII), "text/rfc822-headers")));
        h.setHeader("Content-Type", "text/rfc822-headers");
        report.addBodyPart(h);
        m.setContent(report);
        m.saveChanges();
        return m;
    }

    public static MimeMessage autoReply(String from, String subject, String text, String header, String value) throws Exception {
        MimeMessage m = plain(from, subject, text);
        m.setHeader(header, value);
        m.saveChanges();
        return m;
    }

    /** Как письмо приходит из IMAP: сериализовать и разобрать заново (части — из байтов, а не из объектов). */
    public static MimeMessage roundTrip(MimeMessage m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.writeTo(out);
        return new MimeMessage((Session) null, new ByteArrayInputStream(out.toByteArray()));
    }

    public static byte[] xlsxBytes() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("Наименование");
            h.createCell(1).setCellValue("Кол-во");
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("Аппарат УЗИ");
            r.createCell(1).setCellValue(2);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static MimeBodyPart human() throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setText("This is the mail system at host mx.mail.ru.\n\nYour message could not be delivered.", "UTF-8");
        return p;
    }

    private static MimeBodyPart deliveryStatus(String recipient, String diagnostic) throws Exception {
        String status = "Reporting-MTA: dns; mx.mail.ru\r\n\r\n"
                + "Final-Recipient: rfc822; " + recipient + "\r\n"
                + "Action: failed\r\n"
                + "Status: 5.1.1\r\n"
                + "Diagnostic-Code: smtp; " + diagnostic + "\r\n";
        MimeBodyPart p = new MimeBodyPart();
        p.setDataHandler(new DataHandler(new ByteArrayDataSource(status.getBytes(StandardCharsets.US_ASCII), "message/delivery-status")));
        p.setHeader("Content-Type", "message/delivery-status");
        return p;
    }
}
