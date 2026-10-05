package com.vladoose.nir.service.mail;

/** Готовое уведомление: обычный текст и «без звука». */
public record MailNotification(String text, boolean silent) {}
