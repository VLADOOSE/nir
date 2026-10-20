package com.vladoose.nir.entity;

/** Уровень отметки «наш товар» (спеки 2026-10-19-product-watch §4.3, 2026-10-20-product-spec-fit §6.4): порядок значим. */
public enum ProductMatchLevel {
    /** Наш вид изделия. */
    TYPE,
    /** ТЗ сверено с нашим товаром — подходит (РУ действует). */
    FITS,
    /** Назван наш бренд или модель. */
    NAMED,
    /** ТЗ списано с нашего товара. */
    COPIED;

    /** «★ точно наше». */
    public boolean exact() { return this == NAMED || this == COPIED; }

    /** В Telegram в режиме «только ★» (`ProductNotifyPolicy` STAR): подходит или точно наше. */
    public boolean alert() { return this != TYPE; }

    public static ProductMatchLevel max(ProductMatchLevel a, ProductMatchLevel b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
