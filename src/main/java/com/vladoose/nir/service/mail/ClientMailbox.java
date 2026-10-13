package com.vladoose.nir.service.mail;

import jakarta.mail.MessagingException;
import org.springframework.stereotype.Component;

/**
 * IMAP-ящик писем клиентов (info@): только чтение, как zakup@, но с папкой «Отправленные» и файлами вложений до
 * предела. Отдельный компонент, а не второй MailboxConnector: тот один на приём zakup@.
 */
@Component
public class ClientMailbox {

    private final ImapMailboxConnector connector;

    public ClientMailbox(ClientMailSettings s) {
        this.connector = new ImapMailboxConnector(s.host(), s.port(), s.username(), s.password(), s.protocol(),
                ImapMailboxConnector.CONNECT_TIMEOUT_MS, ImapMailboxConnector.READ_TIMEOUT_MS, s.maxFileBytes());
    }

    public MailboxSession open(MailFolder folder) throws MessagingException {
        return connector.open(folder);
    }
}
