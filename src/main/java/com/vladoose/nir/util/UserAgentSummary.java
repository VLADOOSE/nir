package com.vladoose.nir.util;

/** «iPhone · Safari» из User-Agent — чтобы админ узнал устройство в списке (спека device-gate §7). */
public final class UserAgentSummary {

    private UserAgentSummary() {}

    public static String describe(String ua) {
        if (ua == null || ua.isBlank()) return "Браузер";
        String os = os(ua);
        String browser = browser(ua);
        if (os == null && browser == null) return "Браузер";
        if (os == null) return browser;
        if (browser == null) return os;
        return os + " · " + browser;
    }

    private static String os(String ua) {
        if (ua.contains("iPhone")) return "iPhone";          // раньше Mac: в строке iPhone есть «like Mac OS X»
        if (ua.contains("iPad")) return "iPad";
        if (ua.contains("Android")) return "Android";        // раньше Linux: в строке Android есть и «Linux»
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Macintosh") || ua.contains("Mac OS X")) return "Mac";
        if (ua.contains("Linux")) return "Linux";
        return null;
    }

    private static String browser(String ua) {
        // порядок важен: Edge, Opera и Яндекс выдают себя за Chrome, а Chrome — за Safari
        if (ua.contains("Edg/") || ua.contains("EdgiOS/") || ua.contains("EdgA/")) return "Edge";
        if (ua.contains("OPR/")) return "Opera";
        if (ua.contains("YaBrowser/")) return "Яндекс";
        if (ua.contains("Firefox/") || ua.contains("FxiOS/")) return "Firefox";
        if (ua.contains("Chrome/") || ua.contains("CriOS/")) return "Chrome";
        if (ua.contains("Safari/")) return "Safari";
        return null;
    }
}
