package com.vladoose.nir.util;

/**
 * Размер выдачи списка по параметру {@code limit} (обращения, частные заявки, КП клиенту; 2026-10-18): глобальный
 * поиск показывает по 5 в группе и не тянет ради них по 300 полных строк. Не задан — прежний предел списка.
 */
public final class ListLimit {

    private ListLimit() {}

    /** Не задан — {@code max}; иначе в пределах 1…{@code max} (0 и меньше — одна строка, больше предела — предел). */
    public static int of(Integer requested, int max) {
        if (requested == null) return max;
        return Math.max(1, Math.min(requested, max));
    }
}
