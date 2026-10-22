package com.vladoose.nir.entity;

/** Итог лота на площадке (спека 2026-10-22-tender-results-market-prices §4). */
public enum LotOutcome {
    PENDING, DONE, FAILED, CANCELLED;

    /** Справочник ref_lots_status: 360 — состоялась, 370 — не состоялась, 410 / 430 — отказ и отмена; остальное — ждём. */
    public static LotOutcome ofLotStatus(Integer statusId) {
        if (statusId == null) return PENDING;
        return switch (statusId) {
            case 360 -> DONE;
            case 370 -> FAILED;
            case 410, 430 -> CANCELLED;
            default -> PENDING;
        };
    }
}
