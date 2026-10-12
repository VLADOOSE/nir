package com.vladoose.nir.util;

import com.vladoose.nir.entity.MedRegistry;

/**
 * Текст регистрации строки КП из записи реестра (спека 2026-10-12 §8): «№ РК-МИ (МТ)-0№023037 от 28.10.2021 г., бессрочно»
 * или «…, до 28.10.2026 г.». Без даты регистрации — «№ …» и срок; записи нет — только номер. Общий помощник: им же
 * воспользуется часть 3 волны 2 (реестр в редакторе КП).
 */
public final class RegistrationText {

    private RegistrationText() {}

    public static String of(String regNumber, MedRegistry record) {
        if (regNumber == null || regNumber.isBlank()) return null;
        StringBuilder sb = new StringBuilder("№ ").append(regNumber.trim());
        if (record == null) return sb.toString();
        if (record.getRegDate() != null) sb.append(" от ").append(DocFormat.date(record.getRegDate())).append(" г.");
        if (Boolean.TRUE.equals(record.getUnlimited())) {
            sb.append(", бессрочно");
        } else if (record.getExpirationDate() != null) {
            sb.append(", до ").append(DocFormat.date(record.getExpirationDate())).append(" г.");
        }
        return sb.toString();
    }
}
