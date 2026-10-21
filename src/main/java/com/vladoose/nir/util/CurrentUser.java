package com.vladoose.nir.util;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Логин вошедшего (у passkeys принципал — не UserDetails: только getName(), CLAUDE.md §8). */
public final class CurrentUser {
    private CurrentUser() {}

    /** null — система: фоновый поток, аноним. */
    public static String name() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) return null;
        String n = a.getName();
        return n == null || n.isBlank() ? null : n;
    }

    private static final ThreadLocal<String> VIA = new ThreadLocal<>();

    /** Откуда действие — «Telegram» у кнопок бота (спека 2026-10-21-tender-telegram-bot §8); null — снять. */
    public static void setVia(String via) {
        if (via == null) VIA.remove(); else VIA.set(via);
    }

    /** Автор для журналов: «vlad», из бота — «vlad, Telegram»; null — система (фоновый поток, аноним). */
    public static String label() {
        String n = name();
        if (n == null) return null;
        String v = VIA.get();
        return v == null ? n : n + ", " + v;
    }
}
