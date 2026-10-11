package com.vladoose.nir.entity;

import java.time.ZoneId;

public enum Market {
    RF("RUB", "₽", "ООО «РЕГИОН-МЕД»", "Europe/Samara"),
    KZ("KZT", "₸", "ТОО «West-Med»", "Asia/Oral");

    private final String currencyCode;
    private final String currencySymbol;
    private final String companyShortName;
    private final ZoneId zone;

    Market(String currencyCode, String currencySymbol, String companyShortName, String zone) {
        this.currencyCode = currencyCode;
        this.currencySymbol = currencySymbol;
        this.companyShortName = companyShortName;
        this.zone = ZoneId.of(zone);
    }

    public String currencyCode()     { return currencyCode; }
    public String currencySymbol()   { return currencySymbol; }
    public String companyShortName() { return companyShortName; }

    /**
     * Часы рынка — по ним «сегодня» и сроки площадок: прод живёт в UTC, а срок «до 10:25» — местное время
     * (KZ — Уральск, UTC+5, как весь Казахстан с 2024-03-01; RF — Самара, UTC+4).
     */
    public ZoneId zone()             { return zone; }

    /** Парсит заголовок X-Market; неизвестное/пустое → RF. */
    public static Market fromHeader(String raw) {
        if (raw == null) return RF;
        try { return Market.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return RF; }
    }
}
