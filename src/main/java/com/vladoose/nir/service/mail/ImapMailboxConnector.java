package com.vladoose.nir.service.mail;

import jakarta.mail.*;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * IMAP-ящик только на чтение (спека §3.1): EXAMINE + BODY.PEEK — отметки «прочитано» у людей не трогаем. Таймауты
 * на соединение, чтение и запись: без них зависшее соединение навсегда заняло бы поток приёма.
 */
@Component
public class ImapMailboxConnector implements MailboxConnector {

    static final int CONNECT_TIMEOUT_MS = 20_000;
    static final int READ_TIMEOUT_MS = 60_000;
    static final int WRITE_TIMEOUT_MS = 20_000;
    /**
     * Запас поиска окна по дате. SEARCH SINCE сравнивает только даты (RFC 3501), причём дату письма сервер берёт в
     * своём поясе, а нашу дату Jakarta Mail пишет в поясе JVM: при разных поясах сервер срезал бы письма окна, которые
     * у него пришли «вчера». С запасом в сутки сервер отдаёт лишнее, точное время дофильтровывается в коде.
     */
    static final Duration SEARCH_MARGIN = Duration.ofDays(1);

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String protocol;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    @Autowired
    public ImapMailboxConnector(@Value("${mail.imap.host:localhost}") String host,
                                @Value("${mail.imap.port:3143}") int port,
                                @Value("${mail.imap.username:}") String username,
                                @Value("${mail.imap.password:}") String password,
                                @Value("${mail.imap.protocol:imap}") String protocol) {
        this(host, port, username, password, protocol, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    ImapMailboxConnector(String host, int port, String username, String password, String protocol,
                         int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.protocol = protocol;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public MailboxSession open() throws MessagingException {
        Properties props = new Properties();
        String p = "mail." + protocol + ".";
        props.put(p + "connectiontimeout", String.valueOf(connectTimeoutMs));
        props.put(p + "timeout", String.valueOf(readTimeoutMs));
        props.put(p + "writetimeout", String.valueOf(WRITE_TIMEOUT_MS));
        props.put(p + "peek", "true");                  // тело — BODY.PEEK[]: «прочитано» не ставится
        Store store = Session.getInstance(props).getStore(protocol);
        store.connect(host, port, username, password);
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);                // EXAMINE: флаги не меняются
            return new ImapSession(store, inbox);
        } catch (MessagingException | RuntimeException e) {
            closeQuietly(store);
            throw e;
        }
    }

    private static void closeQuietly(Store store) {
        try {
            store.close();
        } catch (Exception ignored) {
        }
    }

    static final class ImapSession implements MailboxSession {

        private final Store store;
        private final Folder folder;
        private final UIDFolder uids;

        ImapSession(Store store, Folder folder) {
            this.store = store;
            this.folder = folder;
            this.uids = (UIDFolder) folder;
        }

        @Override
        public long uidValidity() throws MessagingException {
            return uids.getUIDValidity();
        }

        @Override
        public long maxUid() throws MessagingException {
            int n = folder.getMessageCount();
            return n == 0 ? 0 : uids.getUID(folder.getMessage(n));
        }

        @Override
        public List<Long> uidsAfter(long lastUid, int limit) throws MessagingException {
            Message[] msgs = uids.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
            List<Long> out = new ArrayList<>();
            for (Message m : msgs) {
                if (m == null) continue;
                long uid = uids.getUID(m);
                if (uid > lastUid) out.add(uid);           // «UID n:*» отдаёт последнее письмо, даже если его UID < n
            }
            Collections.sort(out);
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        }

        @Override
        public List<Long> uidsReceivedSince(Instant since, int limit) throws MessagingException {
            // сервер — по ДНЮ и с запасом (SEARCH_MARGIN), точное время — ниже
            Message[] msgs = folder.search(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since.minus(SEARCH_MARGIN))));
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);              // с ним приходит и INTERNALDATE — дата получения
            fp.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(msgs, fp);
            List<Long> out = new ArrayList<>();
            for (Message m : msgs) {
                Date d = m.getReceivedDate();
                if (d != null && !d.toInstant().isBefore(since)) out.add(uids.getUID(m));   // точное время — здесь
            }
            Collections.sort(out);
            return out.size() > limit ? new ArrayList<>(out.subList(out.size() - limit, out.size())) : out;
        }

        @Override
        public ParsedMail fetch(long uid) throws Exception {
            Message m = uids.getMessageByUID(uid);
            return m == null ? null : MailParser.parse(m, uid);
        }

        @Override
        public BrokenMail envelope(long uid, Exception cause) {
            String from = null, subject = null, messageId = null;
            OffsetDateTime at = null;
            try {
                Message m = uids.getMessageByUID(uid);
                if (m != null) {                             // поля — без U+0000, как при разборе (MailParser.noNul)
                    try { from = MailParser.from(m); } catch (Exception ignored) { }
                    try { subject = MailParser.subject(m); } catch (Exception ignored) { }
                    try { messageId = MailParser.header(m, "Message-ID"); } catch (Exception ignored) { }
                    at = MailParser.receivedAt(m);
                }
            } catch (Exception ignored) {
            }
            return new BrokenMail(uid, messageId, from, subject, at != null ? at : OffsetDateTime.now(ZoneOffset.UTC),
                    cause.getClass().getSimpleName());
        }

        /**
         * Только папка: письма читаются по её соединению, и обрыв она замечает сама — упавшая команда закрывает её
         * (ответ BYE), а при простое больше секунды isOpen() сам шлёт NOOP. Хранилище не спрашиваем: в Angus
         * store.isConnected() при открытой папке берёт ВТОРОЕ соединение со входом, и если вход отклонят (лимит
         * соединений, сбой авторизации у почты), закрывает хранилище вместе с живой папкой — битое письмо приняли бы
         * за обрыв связи, и проход вставал бы на нём каждый раз с курсором на месте (не говоря о лишнем входе на
         * каждое битое письмо и ожидании таймаута соединения при лежащей сети).
         */
        @Override
        public boolean isAlive() {
            return folder.isOpen();
        }

        @Override
        public void close() {
            try {
                if (folder.isOpen()) folder.close(false);
            } catch (Exception ignored) {
            }
            closeQuietly(store);
        }
    }
}
