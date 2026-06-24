package com.vladoose.nir.entity;

public enum Market {
    RF("RUB", "₽", "ООО «РЕГИОН-МЕД»"),
    KZ("KZT", "₸", "ТОО «West-Med»");

    private final String currencyCode;
    private final String currencySymbol;
    private final String companyShortName;

    Market(String currencyCode, String currencySymbol, String companyShortName) {
        this.currencyCode = currencyCode;
        this.currencySymbol = currencySymbol;
        this.companyShortName = companyShortName;
    }

    public String currencyCode()     { return currencyCode; }
    public String currencySymbol()   { return currencySymbol; }
    public String companyShortName() { return companyShortName; }

    /** Парсит заголовок X-Market; неизвестное/пустое → RF. */
    public static Market fromHeader(String raw) {
        if (raw == null) return RF;
        try { return Market.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return RF; }
    }
}
