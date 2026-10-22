package com.vladoose.nir.dto.response;

/** Контакты заказчика из справочника площадки (телефон бывает неполным). */
public record CustomerContacts(String bin, String name, String phone, String email) {}
