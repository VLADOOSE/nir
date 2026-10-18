package com.vladoose.nir.service.deal;

import com.vladoose.nir.entity.LeadChannel;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Тексты этапов и ленты сделки: даты по поясу рынка, склонения. */
public final class DealTexts {
    private static final DateTimeFormatter DD_MM = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DD_MM_HH = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private DealTexts() {}

    public static String ddMM(OffsetDateTime at, ZoneId zone) { return at == null ? null : DD_MM.format(at.atZoneSameInstant(zone)); }
    public static String ddMM(LocalDate d) { return d == null ? null : DD_MM.format(d); }
    public static String ddMMhhmm(LocalDateTime at) { return at == null ? null : DD_MM_HH.format(at); }

    /** 1 позиция, 2 позиции, 5 позиций, 11 позиций, 21 позиция. */
    public static String plural(long n, String one, String few, String many) {
        long m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
        return many;
    }

    public static String positions(long n) { return n + " " + plural(n, "позиция", "позиции", "позиций"); }
    public static String lots(long n) { return n + " " + plural(n, "лот", "лота", "лотов"); }

    /** «ответил 1 из 3» / «ответили 2 из 5»; ноль — «ответов пока нет — запрошено у N поставщиков». */
    public static String answered(long answered, long of, boolean capital) {
        String s = answered == 0
                ? "ответов пока нет — запрошено у " + of + " " + plural(of, "поставщика", "поставщиков", "поставщиков")
                : plural(answered, "ответил", "ответили", "ответили") + " " + answered + " из " + of;
        return capital ? Character.toUpperCase(s.charAt(0)) + s.substring(1) : s;
    }

    public static String lettersFailed(long n) {
        return n + " " + plural(n, "письмо не ушло", "письма не ушли", "писем не ушли");
    }

    public static String channel(LeadChannel c) {
        if (c == null) return "Обращение";
        return switch (c) {
            case SITE -> "Сайт";
            case PHONE -> "Телефон";
            case WHATSAPP -> "WhatsApp";
            case EMAIL -> "Почта";
            case OTHER -> "Другое";
        };
    }
}
