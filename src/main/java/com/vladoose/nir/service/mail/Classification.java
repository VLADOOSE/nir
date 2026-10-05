package com.vladoose.nir.service.mail;

/** Вид письма и id запроса КП из метки [КП-id] (null — метки нет). */
public record Classification(MailClass mailClass, Long kpId) {}
