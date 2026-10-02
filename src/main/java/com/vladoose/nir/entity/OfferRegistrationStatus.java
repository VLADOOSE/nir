package com.vladoose.nir.entity;

/**
 * Регистрация строки КП. Печатается только CONFIRMED / NOT_REQUIRED / MANUAL; SUGGESTED — подсказка реестра,
 * не печатается. CONFIRMED и SUGGESTED ставит только сервер (волна 2); имя не RegistrationStatus — тот занят каталогом.
 */
public enum OfferRegistrationStatus { UNCHECKED, SUGGESTED, CONFIRMED, NOT_REQUIRED, MANUAL }
