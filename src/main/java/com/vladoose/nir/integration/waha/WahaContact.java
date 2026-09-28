package com.vladoose.nir.integration.waha;

/** Контакт WAHA: name — из записной книжки рабочего телефона, pushname — из профиля WhatsApp самого человека. */
public record WahaContact(String id, String name, String pushname) {}
