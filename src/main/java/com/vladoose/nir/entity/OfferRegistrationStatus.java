package com.vladoose.nir.entity;

/**
 * Регистрация строки КП. Печатается только CONFIRMED / NOT_REQUIRED / MANUAL; SUGGESTED — подсказка реестра, NOT_FOUND —
 * проверено, в реестре нет (спека 2026-10-13 §2.1) — оба не печатаются. CONFIRMED и SUGGESTED — только с № из реестра
 * (сервер проверяет, текст подтверждённой собирает сам); имя не RegistrationStatus — тот занят каталогом.
 */
public enum OfferRegistrationStatus { UNCHECKED, SUGGESTED, CONFIRMED, NOT_REQUIRED, MANUAL, NOT_FOUND }
