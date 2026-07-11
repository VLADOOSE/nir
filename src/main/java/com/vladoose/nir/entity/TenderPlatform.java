package com.vladoose.nir.entity;

/** Площадка (канал) KZ-тендера. null → фолбэк по рынку (goszakup для KZ, zakupki для RF). */
public enum TenderPlatform {
    GOSZAKUP,     // goszakup.gov.kz
    SK_PHARMACY   // fms.ecc.kz (СК-Фармация, единый дистрибьютор)
}
