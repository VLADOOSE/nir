package com.vladoose.nir.service.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Тексты исполнения: суммы (копейки — только ненулевые), проценты, «ДД.ММ». Пробелы — неразрывные. */
public final class ExecutionTexts {

    static final char NB = ' ';
    private static final DateTimeFormatter DD_MM = DateTimeFormatter.ofPattern("dd.MM");

    private ExecutionTexts() {}

    private static DecimalFormat format(String pattern) {
        DecimalFormatSymbols sy = new DecimalFormatSymbols(Locale.ROOT);
        sy.setGroupingSeparator(NB);
        sy.setDecimalSeparator(',');
        DecimalFormat f = new DecimalFormat(pattern, sy);
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f;
    }

    /** «3 070 455 тг», «1 535 227,50 тг»; currency null — без валюты. */
    public static String money(BigDecimal v, String currency) {
        if (v == null) return "";
        boolean whole = v.stripTrailingZeros().scale() <= 0;
        String n = format(whole ? "#,##0" : "#,##0.00").format(v);
        return currency == null ? n : n + NB + currency(currency);
    }

    /** Валюта рядом с числом: тг, руб., $, €, юань (как на фронте, shared/deal-execution.ts). */
    public static String currency(String code) {
        if (code == null) return "";
        return switch (code) {
            case "KZT" -> "тг";
            case "RUB" -> "руб.";
            case "USD" -> "$";
            case "EUR" -> "€";
            case "CNY" -> "юань";
            default -> code;
        };
    }

    /** «50 %», «12,5 %». */
    public static String pct(BigDecimal p) {
        return p.stripTrailingZeros().toPlainString().replace('.', ',') + NB + "%";
    }

    public static String ddMM(LocalDate d) {
        return d == null ? null : DD_MM.format(d);
    }
}
