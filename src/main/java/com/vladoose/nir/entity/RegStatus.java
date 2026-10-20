package com.vladoose.nir.entity;

/** Статус РУ нашего товара (спека 2026-10-20-product-spec-fit §4.2, решение 8 плана). */
public enum RegStatus {
    ACTIVE, EXPIRED, NOT_FOUND, NOT_SPECIFIED, UNKNOWN;

    /** Хвост «подходит по ТЗ, но …» — для чипа и сообщения. */
    public String noRuText() {
        return switch (this) {
            case ACTIVE -> "";
            case EXPIRED -> "РУ истекло";
            case NOT_FOUND -> "РУ не найдено в реестре";
            case NOT_SPECIFIED -> "РУ не указано на сайте";
            case UNKNOWN -> "РУ не проверено";
        };
    }
}
