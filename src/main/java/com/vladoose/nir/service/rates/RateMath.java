package com.vladoose.nir.service.rates;

import com.vladoose.nir.exception.BadRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Пересчёт по курсу (спека 2026-10-23 §4.3) — один на всё: ответы поставщиков, прайсы, закупка КП. */
public final class RateMath {

    public static final BigDecimal MAX_RATE = new BigDecimal("100000");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private RateMath() {}

    /** Сумма × курс одной дробью до 0,01 (HALF_UP); не помещается в NUMERIC(15,2) — null (не пересчитывать). */
    public static BigDecimal convert(BigDecimal amount, BigDecimal rate) {
        if (amount == null || rate == null) return null;
        BigDecimal v = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return v.precision() - v.scale() > 13 ? null : v;
    }

    /** «6», «5,48» — курс без лишних нулей. */
    public static String text(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** Курс из ввода: > 0, ≤ 100 000, ≤ 6 знаков после запятой; иначе 400. null — курса нет (снять). */
    public static BigDecimal check(BigDecimal rate) {
        if (rate == null) return null;
        if (rate.signum() <= 0) throw new BadRequestException("Курс — число больше нуля");
        if (rate.compareTo(MAX_RATE) > 0) throw new BadRequestException("Курс слишком большой — не больше 100 000");
        if (rate.stripTrailingZeros().scale() > 6) throw new BadRequestException("Курс — не больше 6 знаков после запятой");
        return rate;
    }

    /** Запас нашего курса к курсу сегодня, % до десятых: 6 при 5,48 — 9,5. */
    public static BigDecimal marginPct(BigDecimal our, BigDecimal today) {
        if (our == null || today == null || today.signum() <= 0) return null;
        return our.subtract(today).multiply(HUNDRED).divide(today, 1, RoundingMode.HALF_UP);
    }
}
