package com.vladoose.nir.service.mail;

import jakarta.mail.MessagingException;

/** Открывает сессию с ящиком; за интерфейсом — чтобы проход приёма тестировался без IMAP. */
public interface MailboxConnector {
    MailboxSession open() throws MessagingException;
}
