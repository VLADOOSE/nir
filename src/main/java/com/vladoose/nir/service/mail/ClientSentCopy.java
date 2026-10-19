package com.vladoose.nir.service.mail;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Копия письма клиенту — в «Отправленные» info@ (спека 2026-10-19-client-mail-reply §3.4): тот же SentCopy, что у zakup@,
 * с IMAP-входом ящика клиентов и своим потоком. Выключатель — mail.clients.sent-copy.enabled; без SMTP — выключена.
 */
@Component
public class ClientSentCopy {

    private final SentCopy copy;

    @Autowired
    public ClientSentCopy(ClientSmtpSettings smtp, ClientMailSettings imap) {
        this(smtp, imap, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mail-clients-sent-copy");
            t.setDaemon(true);
            return t;
        }));
    }

    ClientSentCopy(ClientSmtpSettings smtp, ClientMailSettings imap, Executor executor) {
        this.copy = new SentCopy(smtp.sentCopy() && smtp.enabled(), imap.username(), imap.host(), imap.port(),
                imap.username(), imap.password(), imap.protocol(), executor);
    }

    /** Письмо ушло — копия своим потоком; ничего не бросает. */
    public void store(MimeMessage sent) {
        copy.store(sent);
    }

    @PreDestroy
    void shutdown() {
        copy.shutdown();
    }
}
