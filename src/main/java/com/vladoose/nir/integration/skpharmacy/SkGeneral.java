package com.vladoose.nir.integration.skpharmacy;

/**
 * Поля вкладки «Общие сведения» объявления СК-Фармации (fms.ecc.kz, ?tab=general).
 * Организатор (единый дистрибьютор / лизингодатель) с БИН + юр. адрес (с КАТО и регионом) + контакт.
 * Регион (каноническое имя) считает writer через RegionResolver — здесь только сырой адрес.
 */
public record SkGeneral(
        String customerBin,    // БИН организатора (12 цифр)
        String legalAddress,   // юр. адрес организатора (для резолва региона)
        String regionKato,     // код КАТО из адреса (9 цифр)
        String contactEmail,   // e-mail секретаря
        String contactName     // ФИО секретаря
) {}
