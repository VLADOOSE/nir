package com.vladoose.nir.service.tenderresults.protocol;

import com.vladoose.nir.entity.FailReason;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Числа, время и причины провала в протоколах. */
final class ProtocolNums {

    private static final Pattern DATE_TIME = Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2})");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final Pattern COUNT = Pattern.compile(":\\s*(\\d+)\\s*$");

    private ProtocolNums() {}

    /** «251000», «274787.77», «45 000.00» → число; не число — null. */
    static BigDecimal money(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[\\s\\u00A0]", "").replace(',', '.');
        return t.matches("\\d+(\\.\\d+)?") ? new BigDecimal(t) : null;
    }

    /** «13.10.2026 17:42:30» (и с долями секунды) → время площадки. */
    static LocalDateTime dateTime(String s) {
        Matcher m = DATE_TIME.matcher(s == null ? "" : s);
        if (!m.find()) return null;
        try {
            return LocalDateTime.parse(m.group(1) + " " + m.group(2), FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** «…предложения: 10», «(лоте): 2» → число в конце; нет — 0. */
    static int count(String line) {
        Matcher m = COUNT.matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Причина провала по выводу протокола: одна заявка — раньше «отсутствия» (в одной фразе бывают оба слова). */
    static FailReason reason(String conclusion) {
        String t = conclusion == null ? "" : conclusion.toLowerCase(Locale.ROOT);
        if (t.contains("одного ценового предложения") || t.contains("одной заявки") || t.contains("единственной заявки")) {
            return FailReason.ONE_BID;
        }
        if (t.contains("отсутстви")) return FailReason.NO_BIDS;
        if (t.contains("отказал")) return FailReason.REFUSED;
        return FailReason.OTHER;
    }

    static boolean isBin(String s) {
        return s != null && s.trim().matches("\\d{12}");
    }
}
