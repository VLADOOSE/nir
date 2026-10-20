package com.vladoose.nir.entity;

/** Уровень отметки «наш товар» (спека 2026-10-19-product-watch §4.3): порядок значим — TYPE < NAMED < COPIED. */
public enum ProductMatchLevel {
    /** Наш вид изделия. */
    TYPE,
    /** Назван наш бренд или модель. */
    NAMED,
    /** ТЗ списано с нашего товара. */
    COPIED;

    /** «★ точно наше». */
    public boolean exact() { return this != TYPE; }

    public static ProductMatchLevel max(ProductMatchLevel a, ProductMatchLevel b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
