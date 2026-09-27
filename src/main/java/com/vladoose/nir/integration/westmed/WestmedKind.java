package com.vladoose.nir.integration.westmed;

/** Вид заявки сайта: по префиксу внешнего id понятно, в какой эндпоинт писать статус. */
public enum WestmedKind {
    PRICE("price", "requests"),
    QUOTE("quote", "quote-requests");

    private final String prefix;
    private final String path;

    WestmedKind(String prefix, String path) {
        this.prefix = prefix;
        this.path = path;
    }

    public String path() { return path; }

    public String externalId(String siteId) { return prefix + ":" + siteId; }

    public static WestmedKind ofExternalId(String externalId) {
        for (WestmedKind k : values()) {
            if (externalId != null && externalId.startsWith(k.prefix + ":")) return k;
        }
        throw new IllegalArgumentException("Неизвестный внешний id westmed: " + externalId);
    }

    public static String siteIdOf(String externalId) {
        return externalId.substring(externalId.indexOf(':') + 1);
    }
}
