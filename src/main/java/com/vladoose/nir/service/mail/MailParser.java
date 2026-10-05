package com.vladoose.nir.service.mail;

import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Разбор письма в {@link ParsedMail}: рекурсивный обход multipart (у настоящих писем — вложенный multipart/alternative),
 * имена файлов декодируются RFC 2047, Excel распознаётся и по расширению, и по Content-Type. Части возврата (DSN) —
 * отдельно: message/delivery-status, message/rfc822 (исходное письмо — только тема, текст в тело не идёт),
 * text/rfc822-headers.
 */
public final class MailParser {

    static final int MAX_TEXT = 200_000;      // дальше текст не копим — защита памяти
    static final int MAX_DEPTH = 10;
    static final int MAX_DSN = 20_000;
    private static final List<String> AUTO_HEADERS = List.of("Auto-Submitted", "X-Autoreply", "X-Autorespond", "Precedence");

    private MailParser() {}

    public static ParsedMail parse(Message msg, long uid) throws MessagingException, IOException {
        String from = from(msg);
        String subject = subject(msg);
        Walk w = new Walk();
        walk(msg, w, 0);
        return new ParsedMail(uid, header(msg, "Message-ID"), from, addressPart(from),
                subject == null ? "" : subject, receivedAt(msg), contentType(msg), autoHeaders(msg),
                w.text.toString(), w.html.toString(), List.copyOf(w.attachments), w.excel, w.excelName, w.bounce());
    }

    /**
     * Отправитель для людей: «Имя <адрес>» без кавычек (InternetAddress.toUnicodeString взял бы кириллическое имя
     * в кавычки); битый From — сырое значение, декодированное RFC 2047.
     */
    static String from(Message msg) throws MessagingException {
        try {
            Address[] a = msg.getFrom();
            if (a == null || a.length == 0) return "";
            if (a[0] instanceof InternetAddress ia) {
                String name = noNul(ia.getPersonal());
                String addr = ia.getAddress() == null ? "" : noNul(ia.getAddress());
                return name == null || name.isBlank() ? addr : name.strip() + " <" + addr + ">";
            }
            return decode(a[0].toString());
        } catch (AddressException e) {
            String raw = header(msg, "From");
            return raw == null ? "" : decode(raw);
        }
    }

    /** Тема (RFC 2047 декодирован) без U+0000 ({@link #noNul}); null — темы нет. */
    static String subject(Message msg) throws MessagingException {
        return noNul(msg.getSubject());
    }

    /**
     * Строка без U+0000. PostgreSQL не хранит его в text (SQLSTATE 22021), а прислать его может кто угодно — например,
     * RFC 2047 «=?UTF-8?B?AA==?=» в теме: письмо не записалось бы ни целиком, ни коротко, и проход вставал бы на нём
     * каждую минуту. Поэтому каждая строка, которая приходит из Jakarta Mail в разбор, проходит через этот срез.
     */
    static String noNul(String s) {
        return s == null || s.indexOf('\0') < 0 ? s : s.replace("\0", "");
    }

    /** Адресная часть «Имя <a@b>» → «a@b», нижним регистром. */
    public static String addressPart(String from) {
        if (from == null) return "";
        String s = from.trim();
        int lt = s.lastIndexOf('<'), gt = s.lastIndexOf('>');
        if (lt >= 0 && gt > lt) s = s.substring(lt + 1, gt);
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /** Время получения: дата сервера → дата отправки → сейчас. */
    static OffsetDateTime receivedAt(Message msg) {
        try {
            Date d = msg.getReceivedDate();
            if (d == null) d = msg.getSentDate();
            if (d != null) return d.toInstant().atOffset(ZoneOffset.UTC);
        } catch (MessagingException ignored) {
        }
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    static String header(Part p, String name) throws MessagingException {
        String[] v = p.getHeader(name);
        return v == null || v.length == 0 ? null : noNul(v[0]).trim();
    }

    private static String contentType(Message msg) throws MessagingException {
        String ct = noNul(msg.getContentType());
        return ct == null ? "" : ct.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> autoHeaders(Message msg) throws MessagingException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String h : AUTO_HEADERS) {
            String v = header(msg, h);
            if (v != null) out.put(h.toLowerCase(Locale.ROOT), v.toLowerCase(Locale.ROOT));
        }
        return Map.copyOf(out);
    }

    private static void walk(Part part, Walk w, int depth) throws MessagingException, IOException {
        if (depth > MAX_DEPTH) return;
        if (part.isMimeType("multipart/*")) {
            if (part.getContent() instanceof Multipart mp) {
                for (int i = 0; i < mp.getCount(); i++) walk(mp.getBodyPart(i), w, depth + 1);
            }
            return;
        }
        if (part.isMimeType("message/delivery-status")) {
            w.deliveryStatus(readText(part, MAX_DSN));
            return;
        }
        if (part.isMimeType("message/rfc822")) {
            w.originalSubject = nestedSubject(part);
            String name = decode(part.getFileName());
            if (name != null) w.attachments.add(name);
            return;
        }
        if (part.isMimeType("text/rfc822-headers")) {
            try (InputStream in = part.getInputStream()) {
                String s = new InternetHeaders(in).getHeader("Subject", null);
                if (s != null) w.originalSubject = decode(MimeUtility.unfold(s));
            }
            return;
        }
        String fileName = decode(part.getFileName());
        boolean excel = isExcel(part, fileName);
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                || (fileName != null && !embeddedImage(part)) || excel;
        if (excel && w.excel == null) {
            try (InputStream in = part.getInputStream()) {
                w.excel = in.readAllBytes();
            }
            w.excelName = fileName != null ? fileName : "attachment.xlsx";
        }
        if (attachment) {
            w.attachments.add(fileName != null ? fileName : "без имени");
        } else if (part.isMimeType("text/plain")) {
            append(w.text, text(part));
        } else if (part.isMimeType("text/html")) {
            append(w.html, text(part));
        }
    }

    /**
     * Тема вложенного письма (исходное письмо в возврате) — сначала как у вложенного Message: по IMAP это конверт из
     * BODYSTRUCTURE (письмо целиком не качается), из байтов — разобранный MimeMessage. Только поток части не годится:
     * по IMAP это BODY[n] части, и сервер может отдать лишь ТЕЛО вложенного письма без заголовков (так делает
     * GreenMail) — тема терялась, и возврат не привязывался к запросу КП. Поток — запасной путь: содержимое не письмо
     * или у конверта нет темы (NIL).
     */
    private static String nestedSubject(Part part) throws MessagingException, IOException {
        if (part.getContent() instanceof Message nested) {
            String s = noNul(nested.getSubject());
            if (s != null) return s;
        }
        try (InputStream in = part.getInputStream()) {
            return noNul(new MimeMessage((Session) null, in).getSubject());
        }
    }

    private static boolean isExcel(Part part, String fileName) throws MessagingException {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") || n.endsWith(".xls")
                || part.isMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                || part.isMimeType("application/vnd.ms-excel");
    }

    /** Картинка подписи, встроенная в HTML (inline + Content-ID), — не вложение. */
    private static boolean embeddedImage(Part part) throws MessagingException {
        return part.isMimeType("image/*") && part instanceof MimePart mp && mp.getContentID() != null
                && !Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
    }

    private static String text(Part part) throws MessagingException, IOException {
        try {
            Object c = part.getContent();
            return c instanceof String s ? noNul(s) : readText(part, MAX_TEXT);
        } catch (UnsupportedEncodingException e) {    // неизвестная кодировка — байты как UTF-8, письмо не теряем
            return readText(part, MAX_TEXT);
        }
    }

    private static String readText(Part part, int max) throws MessagingException, IOException {
        try (InputStream in = part.getInputStream()) {
            return noNul(new String(in.readNBytes(max), StandardCharsets.UTF_8));
        }
    }

    /**
     * Части тела — через перевод строки: встык «Количество 2» и «1 500 000 тг» (Apple Mail режет текст на части вокруг
     * вставленного файла) прочитались бы как одно число. Срез по пределу не разрезает эмодзи
     * ({@link MailText#safeCut}).
     */
    private static void append(StringBuilder sb, String s) {
        if (s == null || s.isEmpty() || sb.length() >= MAX_TEXT) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(MailText.safeCut(s, MAX_TEXT - sb.length()));
    }

    /** RFC 2047 → текст без U+0000 ({@link #noNul}); битое кодирование — исходная строка (тоже без U+0000). */
    static String decode(String s) {
        if (s == null) return null;
        try {
            return noNul(MimeUtility.decodeText(s));
        } catch (Exception e) {
            return noNul(s);
        }
    }

    /** Поля DSN (RFC 3464): первое значение каждого имени, строки-продолжения склеены. */
    static Map<String, String> dsnFields(String s) {
        Map<String, String> out = new HashMap<>();
        String unfolded = s.replace("\r\n", "\n").replaceAll("\n[ \t]+", " ");
        for (String line : unfolded.split("\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            out.putIfAbsent(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
        }
        return out;
    }

    /** «rfc822; a@b» → «a@b», «smtp; 550 …» → «550 …». */
    private static String afterType(String v) {
        if (v == null) return null;
        int i = v.indexOf(';');
        String s = (i >= 0 ? v.substring(i + 1) : v).trim();
        return s.isEmpty() ? null : s;
    }

    private static final class Walk {
        final StringBuilder text = new StringBuilder();
        final StringBuilder html = new StringBuilder();
        final List<String> attachments = new ArrayList<>();
        byte[] excel;
        String excelName;
        boolean dsn;
        String finalRecipient;
        String status;
        String diagnostic;
        String originalSubject;
        String action;

        void deliveryStatus(String s) {
            dsn = true;
            Map<String, String> f = dsnFields(s);
            finalRecipient = afterType(f.get("final-recipient"));
            if (finalRecipient == null) finalRecipient = afterType(f.get("original-recipient"));
            status = f.get("status");
            diagnostic = afterType(f.get("diagnostic-code"));
            if (diagnostic != null) diagnostic = MailText.safeCut(diagnostic, 200);
            String a = f.get("action");
            action = a == null || a.isBlank() ? null : a.strip().toLowerCase(Locale.ROOT);
        }

        ParsedMail.Bounce bounce() {
            if (!dsn && originalSubject == null) return null;
            return new ParsedMail.Bounce(finalRecipient, status, diagnostic, originalSubject, action);
        }
    }
}
