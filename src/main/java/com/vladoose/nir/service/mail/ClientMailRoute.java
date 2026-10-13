package com.vladoose.nir.service.mail;

/** Куда идёт письмо «Входящих» ящика info@ (спека 2026-10-13-info-mail-leads §2.2). */
public enum ClientMailRoute {
    /** Уведомление сайта о заявке — пропуск: заявка уже пришла обращением через API сайта. */
    SITE_NOTIFICATION,
    BOUNCE, DELAYED, AUTO_REPLY,
    NEWSLETTER, SERVICE, OWN_DOMAIN,
    /** Письмо собеседника — в переписку и обращения. */
    CONVERSATION
}
