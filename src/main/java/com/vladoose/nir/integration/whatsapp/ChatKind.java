package com.vladoose.nir.integration.whatsapp;

/** Вид чата по chatId: @c.us — личный с номером, @lid — личный со скрытым номером, @g.us — группа. */
public enum ChatKind {
    PERSONAL, PERSONAL_HIDDEN, GROUP;

    /** null — это не чат: истории status@broadcast, каналы …@newsletter, рассылки …@broadcast. */
    public static ChatKind of(String chatId) {
        if (chatId == null || chatId.isBlank()) return null;
        if (chatId.endsWith("@g.us")) return GROUP;
        if (chatId.endsWith("@c.us")) return PERSONAL;
        if (chatId.endsWith("@lid")) return PERSONAL_HIDDEN;
        return null;
    }
}
