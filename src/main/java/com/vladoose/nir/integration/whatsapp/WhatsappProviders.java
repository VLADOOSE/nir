package com.vladoose.nir.integration.whatsapp;

import java.util.Locale;

/** Значения chats.whatsapp.provider (WHATSAPP_PROVIDER). */
public final class WhatsappProviders {

    public static final String WAHA = "waha";
    public static final String GREENAPI = "greenapi";

    private WhatsappProviders() {}

    /** « GreenAPI » → «greenapi»; null → "". */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }
}
