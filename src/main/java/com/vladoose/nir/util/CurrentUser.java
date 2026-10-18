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
}
