package com.vladoose.nir.service.mail;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Письмо клиенту с ящика info@ (спека 2026-10-19-client-mail-reply §3.2): от кого, кому, тема, текст и подпись; у ответа —
 * цитата и Message-ID письма, на которое отвечаем (inReplyTo). messageId ставит АИС заранее — он же ключ в переписке.
 */
public record ClientLetter(String fromName, String fromAddress, List<String> to, String subject, String text,
                           String signature, Quote quote, String inReplyTo, String messageId,
                           List<Attachment> attachments) {

    /** Письмо, на которое отвечаем: когда, от кого (имени может не быть), текст — как хранится в переписке. */
    public record Quote(OffsetDateTime sentAt, String name, String address, String body) {}

    /** Вложение: имя (кириллица кодируется), байты, тип. */
    public record Attachment(String fileName, byte[] bytes, String contentType) {}
}
