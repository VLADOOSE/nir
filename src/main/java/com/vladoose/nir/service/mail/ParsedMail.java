package com.vladoose.nir.service.mail;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Разобранное письмо — всё, что нужно классификации, записи и тексту уведомления. Неизменяемо и без ссылок на
 * Jakarta Mail: разбор идёт ВНЕ транзакции (спека §3.3), запись — потом, отдельным бином.
 *
 * @param messageId       заголовок Message-ID как есть; null — заголовка нет
 * @param from            отправитель для людей: «Имя <адрес>» или адрес; "" — нет
 * @param fromAddress     адрес отправителя нижним регистром; "" — нет
 * @param contentType     верхний Content-Type нижним регистром, пробелы схлопнуты
 * @param autoHeaders     признаки автоответа: имя заголовка нижним регистром → значение нижним регистром
 *                        (auto-submitted, x-autoreply, x-autorespond, precedence)
 * @param text            text/plain письма (не вложения); "" — нет
 * @param html            text/html письма (не вложения); "" — нет
 * @param attachmentNames имена вложений; встроенные картинки подписи (inline + Content-ID) не входят
 * @param excelBytes      первое Excel-вложение или null
 * @param bounce          сведения из частей возврата (DSN); null — таких частей нет
 */
public record ParsedMail(long uid, String messageId, String from, String fromAddress, String subject,
                         OffsetDateTime receivedAt, String contentType, Map<String, String> autoHeaders,
                         String text, String html, List<String> attachmentNames,
                         byte[] excelBytes, String excelName, Bounce bounce) {

    /**
     * Возврат: адресат, код статуса, диагностика сервера, тема исходного письма (декодирована) и действие из отчёта —
     * поле Action (RFC 3464: failed, delayed…) нижним регистром; null — поля нет.
     */
    public record Bounce(String finalRecipient, String status, String diagnostic, String originalSubject, String action) {}

    /** Текст письма: text/plain, а если его нет — HTML, переведённый в текст. */
    public String body() {
        return !text.isBlank() ? text : MailText.htmlToText(html);
    }

    /**
     * Сырое тело: text/plain, а если его нет — HTML как есть, без перевода в текст. Вход для разбора ответа поставщика
     * (цена, отказ): как в прежнем приёме — у них свой разбор HTML.
     */
    public String rawBody() {
        return !text.isBlank() ? text : html;
    }
}
