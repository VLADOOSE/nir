package com.vladoose.nir.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Числа и даты документов КП: «9 902 248,23» (неразрывный пробел между разрядами), «2,5», «5%», «14.09.2026».
 * DecimalFormat не потокобезопасен — создаётся на вызов.
 */
public final class DocFormat {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private DocFormat() {}

    private static DecimalFormat format(String pattern) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        DecimalFormat f = new DecimalFormat(pattern, symbols);
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f;
    }

    public static String money(BigDecimal v) {
        return v == null ? "" : format("#,##0.00").format(v);
    }

    public static String qty(BigDecimal v) {
        return v == null ? "" : format("#,##0.###").format(v);
    }

    /** null — «Без НДС». */
    public static String rate(BigDecimal v) {
        return v == null ? "Без НДС" : format("0.##").format(v) + "%";
    }

    public static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    /** Сокращение валюты в тексте документа: шрифт PDF не содержит знаков ₸ и ₽. */
    public static String currencyShort(String currencyCode) {
        return "RUB".equals(currencyCode) ? "руб." : "тг";
    }
}
