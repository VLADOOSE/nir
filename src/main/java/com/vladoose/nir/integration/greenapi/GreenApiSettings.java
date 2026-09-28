package com.vladoose.nir.integration.greenapi;

/** Нужное АИС из getSettings: номер и флаги, без которых приём молча неполный (спека §7). */
public record GreenApiSettings(String wid, String webhookUrl, boolean incomingWebhook, boolean outgoingMessageWebhook) {}
