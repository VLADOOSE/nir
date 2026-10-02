package com.vladoose.nir.service.offer;

import com.vladoose.nir.exception.BadRequestException;

/**
 * Колонки таблицы КП (спека §6.2): подпись по умолчанию (с НДС / без НДС), относительная ширина (у NAME — остаток),
 * выравнивание, «только при НДС» — такие колонки при выключенном НДС не печатаются.
 */
public enum OfferColumnKey {
    NUM("№", null, 5, ColumnAlign.CENTER, false),
    NAME("Наименование", null, 0, ColumnAlign.LEFT, false),
    MODEL("Модель / артикул", null, 13, ColumnAlign.LEFT, false),
    MANUFACTURER("Производитель", null, 15, ColumnAlign.LEFT, false),
    COUNTRY("Страна", null, 9, ColumnAlign.LEFT, false),
    UNIT("Ед. изм.", null, 7, ColumnAlign.CENTER, false),
    QTY("Кол-во", null, 7, ColumnAlign.CENTER, false),
    PRICE("Цена (с НДС)", "Цена", 12, ColumnAlign.RIGHT, false),
    PRICE_NET("Цена без НДС", null, 12, ColumnAlign.RIGHT, true),
    VAT_RATE("Ставка НДС", null, 8, ColumnAlign.CENTER, true),
    VAT_SUM("Сумма НДС", null, 11, ColumnAlign.RIGHT, true),
    SUM_NET("Сумма без НДС", null, 13, ColumnAlign.RIGHT, true),
    SUM("Сумма (с НДС)", "Сумма", 13, ColumnAlign.RIGHT, false),
    REGISTRATION("Регистрация", null, 17, ColumnAlign.LEFT, false),
    NOTE("Примечание", null, 13, ColumnAlign.LEFT, false);

    private final String label;
    private final String labelNoVat;
    private final int weight;
    private final ColumnAlign align;
    private final boolean vatOnly;

    OfferColumnKey(String label, String labelNoVat, int weight, ColumnAlign align, boolean vatOnly) {
        this.label = label;
        this.labelNoVat = labelNoVat;
        this.weight = weight;
        this.align = align;
        this.vatOnly = vatOnly;
    }

    public String defaultLabel(boolean vatEnabled) {
        return !vatEnabled && labelNoVat != null ? labelNoVat : label;
    }

    public int weight() { return weight; }
    public ColumnAlign align() { return align; }
    public boolean vatOnly() { return vatOnly; }

    public static OfferColumnKey parse(String key) {
        if (key != null) {
            for (OfferColumnKey k : values()) if (k.name().equals(key)) return k;
        }
        throw new BadRequestException("Неизвестная колонка: " + key);
    }
}
