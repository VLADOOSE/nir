package com.vladoose.nir.service.mail;

import jakarta.annotation.PreDestroy;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Копия письма, отправленного АИС по SMTP, — в «Отправленные» того же ящика (IMAP APPEND, с отметкой «прочитано»).
 * Mail.ru кладёт в «Отправленные» только письма своей веб-почты и приложения; почтовые программы дописывают копию сами —
 * так и здесь. Работает, только если ящик приёма (mail.imap.*) — тот же, что ящик отправки (spring.mail.username), и
 * задан его пароль: на проде это zakup@ (письма поставщикам и их ответы). Копия пишется в своём потоке после отправки:
 * её сбой (ящик недоступен, нет папки) письмо не трогает; папку АИС не заводит; в лог — только класс исключения
 * (в текстах исключений почты бывают адреса). Письма, накопившиеся до записи (запрос 20 поставщикам), уходят одним
 * входом в ящик.
 */
@Component
public class SentCopy {

    private static final Logger log = LoggerFactory.getLogger(SentCopy.class);

    private final boolean active;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String protocol;
    private final Executor executor;
    /** Снятые, но ещё не записанные копии: каждая задача забирает всё накопленное — один вход в ящик на пачку. */
    private final Queue<byte[]> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean warnedNoFolder = new AtomicBoolean();

    @Autowired
    public SentCopy(@Value("${mail.sent-copy.enabled:true}") boolean enabled,
                    @Value("${spring.mail.username:}") String from,
                    @Value("${mail.imap.host:localhost}") String host,
                    @Value("${mail.imap.port:3143}") int port,
                    @Value("${mail.imap.username:}") String username,
                    @Value("${mail.imap.password:}") String password,
                    @Value("${mail.imap.protocol:imap}") String protocol) {
        this(enabled, from, host, port, username, password, protocol, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mail-sent-copy");
            t.setDaemon(true);
            return t;
        }));
    }

    SentCopy(boolean enabled, String from, String host, int port, String username, String password, String protocol,
             Executor executor) {
        this.active = enabled && !blank(password) && !blank(from) && !blank(username)
                && from.trim().equalsIgnoreCase(username.trim());
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.protocol = protocol;
        this.executor = executor;
        if (active) log.info("Копии писем АИС — в «Отправленные» {}", username.trim());
    }

    /**
     * Письмо ушло — копию в «Отправленные». Байты снимаются сразу: у ушедшего письма уже есть Message-ID и дата. Не
     * бросает ничего, даже Error: письмо уже ушло, и исход отправки должен записаться.
     */
    public void store(MimeMessage sent) {
        if (!active || sent == null) return;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            sent.writeTo(out);
            pending.add(out.toByteArray());
            executor.execute(this::flush);
        } catch (Throwable e) {
            log.warn("Копия письма в «Отправленные» не сохранена: {}", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService es) es.shutdown();
    }

    /** Всё накопленное — одним входом в ящик; пусто (пачку забрала прошлая задача) — ничего. */
    private void flush() {
        List<byte[]> batch = new ArrayList<>();
        for (byte[] b; (b = pending.poll()) != null; ) batch.add(b);
        if (!batch.isEmpty()) append(batch);
    }

    private void append(List<byte[]> batch) {
        Properties props = new Properties();
        String p = "mail." + protocol + ".";
        props.put(p + "connectiontimeout", String.valueOf(ImapMailboxConnector.CONNECT_TIMEOUT_MS));
        props.put(p + "timeout", String.valueOf(ImapMailboxConnector.READ_TIMEOUT_MS));
        props.put(p + "writetimeout", String.valueOf(ImapMailboxConnector.WRITE_TIMEOUT_MS));
        Session session = Session.getInstance(props);
        Store store = null;
        try {
            store = session.getStore(protocol);
            store.connect(host, port, username, password);
            Folder folder = ImapMailboxConnector.sentFolder(store);
            Message[] copies = new Message[batch.size()];
            for (int i = 0; i < copies.length; i++) {
                MimeMessage copy = new MimeMessage(session, new ByteArrayInputStream(batch.get(i)));
                copy.setFlag(Flags.Flag.SEEN, true);
                copies[i] = copy;
            }
            folder.appendMessages(copies);
        } catch (FolderNotFoundException e) {
            if (warnedNoFolder.compareAndSet(false, true)) {
                log.warn("Копии отправленных писем не сохраняются: в ящике отправки нет папки «Отправленные»");
            }
        } catch (Exception e) {
            log.warn("Копии писем в «Отправленные» не сохранены ({}): {}", batch.size(), e.getClass().getSimpleName());
        } finally {
            if (store != null) {
                try {
                    store.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
