package com.vladoose.nir.service.mail;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.time.Instant;
import java.util.Date;
import java.util.Properties;

/**
 * Проверки и правки ящика GreenMail в обход кода приёма: «прочитано» и «новое» ли письмо, письмо с датой получения
 * в прошлом, удаление письма.
 */
public final class ImapTestSupport {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 3143;                // ServerSetupTest.IMAP
    public static final String USER = "zakup@westmed.kz";
    public static final String PASS = "secret";

    private ImapTestSupport() {}

    /** Стоит ли у письма с этой темой отметка «прочитано» (папка открывается только на чтение — ничего не меняет). */
    public static boolean seen(String subject) throws Exception {
        return hasFlag(subject, Flags.Flag.SEEN);
    }

    /**
     * Осталось ли письмо «новым» (\Recent). Открыть папку на запись (SELECT) — значит забрать эту отметку у всех
     * следующих сессий, то есть у людей; на чтение (EXAMINE) она остаётся (RFC 3501). Проверка тоже открывает папку
     * только на чтение.
     */
    public static boolean recent(String subject) throws Exception {
        return hasFlag(subject, Flags.Flag.RECENT);
    }

    private static boolean hasFlag(String subject, Flags.Flag flag) throws Exception {
        Store s = Session.getInstance(new Properties()).getStore("imap");
        s.connect(HOST, PORT, USER, PASS);
        try {
            Folder f = s.getFolder("INBOX");
            f.open(Folder.READ_ONLY);
            for (Message m : f.getMessages()) {
                if (subject.equals(m.getSubject())) return m.isSet(flag);
            }
            throw new AssertionError("в ящике нет письма «" + subject + "»");
        } finally {
            s.close();
        }
    }

    /** Удалить письмо с этой темой, как человек в веб-почте: писем становится меньше, UID оставшихся не меняются. */
    public static void delete(String subject) throws Exception {
        Store s = Session.getInstance(new Properties()).getStore("imap");
        s.connect(HOST, PORT, USER, PASS);
        try {
            Folder f = s.getFolder("INBOX");
            f.open(Folder.READ_WRITE);
            boolean found = false;
            for (Message m : f.getMessages()) {
                if (subject.equals(m.getSubject())) {
                    m.setFlag(Flags.Flag.DELETED, true);
                    found = true;
                }
            }
            if (!found) throw new AssertionError("в ящике нет письма «" + subject + "»");
            f.close(true);                              // CLOSE с удалением помеченных
        } finally {
            s.close();
        }
    }

    /**
     * APPEND письма с датой получения (INTERNALDATE) в прошлом — как письмо, пришедшее, пока приём не работал.
     * ⚠️ GreenMail 2.1.2 читает время APPEND 12-часовым шаблоном (hh): дата с часом 12 по поясу JVM ляжет на 12 часов
     * раньше. Письму «давно» это не мешает, а письмо «минуту назад» в 12:xx уедет из окна — свежие письма класть deliver.
     */
    public static void appendWithReceivedDate(String subject, Instant receivedAt) throws Exception {
        Store s = Session.getInstance(new Properties()).getStore("imap");
        s.connect(HOST, PORT, USER, PASS);
        try {
            Folder f = s.getFolder("INBOX");
            MimeMessage m = new MimeMessage((Session) null) {
                @Override public Date getReceivedDate() { return Date.from(receivedAt); }
            };
            m.setFrom(new InternetAddress("old@x.kz"));
            m.setSubject(subject, "UTF-8");
            m.setSentDate(Date.from(receivedAt));
            m.setText("Письмо из прошлого", "UTF-8");
            m.saveChanges();
            f.appendMessages(new Message[]{m});
        } finally {
            s.close();
        }
    }
}
