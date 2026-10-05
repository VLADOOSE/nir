package com.vladoose.nir.service.mail;

import jakarta.mail.MessagingException;

import java.time.Instant;
import java.util.List;

/** Ящик, открытый только на чтение: UID, поиск по окну, разбор писем. */
public interface MailboxSession extends AutoCloseable {

    long uidValidity() throws MessagingException;

    /** UID последнего письма ящика; 0 — ящик пуст. */
    long maxUid() throws MessagingException;

    /** UID писем после lastUid по возрастанию, не больше limit. */
    List<Long> uidsAfter(long lastUid, int limit) throws MessagingException;

    /** UID писем, полученных не раньше since, по возрастанию; не больше limit (переполнено — самые свежие). */
    List<Long> uidsReceivedSince(Instant since, int limit) throws MessagingException;

    /** Разобранное письмо; null — письма с таким UID уже нет. */
    ParsedMail fetch(long uid) throws Exception;

    /** Что удалось прочитать о письме, которое не разобралось. */
    BrokenMail envelope(long uid, Exception cause);

    /** Соединение и папка живы: иначе сбой — это обрыв связи, а не битое письмо. */
    boolean isAlive();

    @Override
    void close();
}
