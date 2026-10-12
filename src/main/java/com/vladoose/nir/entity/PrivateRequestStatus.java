package com.vladoose.nir.entity;

/**
 * Статус частной заявки (tender.status у source = PRIVATE_REQUEST): NEW — в работе, остальное — закрыта с исходом.
 * Этап («Запрошены цены…») не хранится — считается из данных (PrivateRequestStageCalculator).
 */
public enum PrivateRequestStatus {
    NEW, WON, LOST, CANCELLED;

    public boolean closed() { return this != NEW; }

    /** Неизвестное значение — «в работе»: так читаются и заявки до V26 (там всегда было NEW). */
    public static PrivateRequestStatus of(String raw) {
        if (raw == null) return NEW;
        try { return valueOf(raw); } catch (IllegalArgumentException e) { return NEW; }
    }
}
