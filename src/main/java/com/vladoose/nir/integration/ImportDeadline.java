package com.vladoose.nir.integration;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Срок приёма заявок с площадки (B2). goszakup v3 ({@code endDate}) и лента СК-Фармации отдают его с временем —
 * «2026-10-09 10:25:00», местное время площадки (Казахстан, UTC+5). Прежде время отрезалось до даты: оператор не
 * видел, что приём закончится в 10:25, а тендер числился принимающим заявки до конца дня.
 */
public final class ImportDeadline {

    private static final Pattern DATE_TIME =
            Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?");

    private ImportDeadline() {}

    /** «ГГГГ-ММ-ДД ЧЧ:ММ[:СС]» (пробел или «T»; хвост вроде долей секунды или пояса не учитывается) → время; без времени или мусор → null. */
    public static LocalDateTime parse(String s) {
        if (s == null) return null;
        Matcher m = DATE_TIME.matcher(s.trim());
        if (!m.lookingAt()) return null;
        try {
            return LocalDateTime.of(num(m, 1), num(m, 2), num(m, 3), num(m, 4), num(m, 5),
                    m.group(6) == null ? 0 : num(m, 6));
        } catch (DateTimeException e) {   // 13-й месяц, 25 часов
            return null;
        }
    }

    /**
     * Приём закончился. Есть время — «сейчас» не раньше него (ровно в срок приём уже закрыт); только дата (ручной
     * тендер) — весь день срока заявки ещё принимают. {@code now} — местное время рынка ({@code Market#zone}).
     */
    public static boolean passed(LocalDate date, LocalDateTime at, LocalDateTime now) {
        if (at != null) return !now.isBefore(at);
        return date != null && date.isBefore(now.toLocalDate());
    }

    private static int num(Matcher m, int group) {
        return Integer.parseInt(m.group(group));
    }
}
