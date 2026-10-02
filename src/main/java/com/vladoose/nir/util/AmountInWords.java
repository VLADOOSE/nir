package com.vladoose.nir.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Сумма прописью для КП (спека §5.3): «Девять миллионов девятьсот две тысячи двести сорок восемь тенге 23 тиын».
 * Целая часть словами (род: тысяча — ж., миллион — м., валюта — м.), копейки/тиыны цифрами. Тенге и тиын не
 * склоняются; рубль и копейка — по правилу 1 / 2–4 / 5–20 (11–14 — всегда «многие»).
 */
public final class AmountInWords {

    private static final String[] ONES_M = {"", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] ONES_F = {"", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] TEENS = {"десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
            "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
    private static final String[] TENS = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят",
            "семьдесят", "восемьдесят", "девяносто"};
    private static final String[] HUNDREDS = {"", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот",
            "семьсот", "восемьсот", "девятьсот"};
    private static final String[][] GROUPS = {
            null,
            {"тысяча", "тысячи", "тысяч"},
            {"миллион", "миллиона", "миллионов"},
            {"миллиард", "миллиарда", "миллиардов"},
            {"триллион", "триллиона", "триллионов"}};
    private static final boolean[] GROUP_FEMININE = {false, true, false, false, false};

    private record Currency(String[] major, String[] minor) {}

    private static final Currency KZT = new Currency(new String[]{"тенге", "тенге", "тенге"}, new String[]{"тиын", "тиын", "тиын"});
    private static final Currency RUB = new Currency(new String[]{"рубль", "рубля", "рублей"}, new String[]{"копейка", "копейки", "копеек"});

    private AmountInWords() {}

    public static String of(BigDecimal amount, String currencyCode) {
        Currency currency = "RUB".equals(currencyCode) ? RUB : KZT;
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        boolean negative = value.signum() < 0;
        value = value.abs();
        long whole = value.longValue();
        int minor = value.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        String words = whole == 0 ? "ноль" : spell(whole);
        String text = (negative ? "минус " : "") + words + " " + currency.major()[form(whole)]
                + " " + String.format("%02d", minor) + " " + currency.minor()[form(minor)];
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String spell(long number) {
        List<String> words = new ArrayList<>();
        int group = 0;
        long n = number;
        while (n > 0) {
            if (group >= GROUPS.length) throw new IllegalArgumentException("Слишком большая сумма: " + number);
            int triad = (int) (n % 1000);
            if (triad != 0) {
                List<String> part = triad(triad, GROUP_FEMININE[group]);
                if (group > 0) part.add(GROUPS[group][form(triad)]);
                words.addAll(0, part);
            }
            n /= 1000;
            group++;
        }
        return String.join(" ", words);
    }

    private static List<String> triad(int n, boolean feminine) {
        List<String> w = new ArrayList<>();
        int h = n / 100, t = (n / 10) % 10, o = n % 10;
        if (h > 0) w.add(HUNDREDS[h]);
        if (t == 1) {
            w.add(TEENS[o]);
        } else {
            if (t > 1) w.add(TENS[t]);
            if (o > 0) w.add(feminine ? ONES_F[o] : ONES_M[o]);
        }
        return w;
    }

    /** 0 — «один рубль», 1 — «два рубля», 2 — «пять рублей». */
    static int form(long n) {
        long m100 = n % 100, m10 = n % 10;
        if (m100 >= 11 && m100 <= 14) return 2;
        if (m10 == 1) return 0;
        if (m10 >= 2 && m10 <= 4) return 1;
        return 2;
    }
}
