package com.vladoose.nir.service.mail;

/** Вид письма ящика (спека §4.1): от него зависят строка «Входящих», правки запроса КП и текст уведомления. */
public enum MailClass {
    /** Уведомление сайта о заявке — не записывается (заявки приходят через API сайта). */
    SITE_NOTIFICATION,
    /** Своё письмо (From = адрес отправки КП) — записывается без уведомления. */
    OWN,
    BOUNCE,
    AUTO_REPLY,
    SUPPLIER_RESPONSE,
    CLIENT_REQUEST,
    UNMATCHED
}
