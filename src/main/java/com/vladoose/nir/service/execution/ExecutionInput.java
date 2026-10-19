package com.vladoose.nir.service.execution;

import com.vladoose.nir.entity.ContractKind;
import com.vladoose.nir.exception.BadRequestException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Проверка ввода исполнения сделки (спека 2026-10-19-deal-execution §7): всё — до записи, отказ — 400 с русским текстом
 * (аннотации проверки отдают «Ошибка валидации данных» без поля — решение при планировании 9). Масштаб и число цифр
 * суммы — до округления: число вроде 1e200000000 иначе держало бы процессор (CLAUDE.md §14).
 */
public final class ExecutionInput {

    public static final int NOTE_MAX = 2000;
    public static final int NUMBER_MAX = 100;
    public static final BigDecimal FULL_PREPAY = new BigDecimal("100.00");
    public static final List<String> CURRENCIES = List.of("KZT", "RUB", "USD", "EUR", "CNY");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private ExecutionInput() {}

    /** Сумма из поля: пробелы между разрядами (и неразрывные), запятая или точка. */
    public static BigDecimal amount(String raw, String label) {
        BigDecimal v;
        try {
            v = new BigDecimal(raw.replaceAll("[\\s\\u00A0\\u202F]", "").replace(',', '.'));
        } catch (NumberFormatException | NullPointerException e) {
            throw new BadRequestException(label + ": нужно число, например 1 535 228");
        }
        return amount(v, label);
    }

    /** > 0, не больше 13 цифр до запятой и 2 после (предел NUMERIC(15,2)). */
    public static BigDecimal amount(BigDecimal v, String label) {
        if (v == null) throw new BadRequestException(label + ": укажите сумму");
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() > 2) throw new BadRequestException(label + ": не больше 2 знаков после запятой");
        if (s.precision() - s.scale() > 13) {
            throw new BadRequestException(label + ": слишком большая сумма — не больше 13 цифр до запятой");
        }
        if (s.signum() <= 0) throw new BadRequestException(label + ": сумма должна быть больше нуля");
        return s.setScale(2);
    }

    /** Предоплата, %: 0…100, не больше 2 знаков после запятой; «%» и пробелы допускаются. */
    public static BigDecimal pct(String raw) {
        BigDecimal v;
        try {
            v = new BigDecimal(raw.replaceAll("[\\s\\u00A0\\u202F%]", "").replace(',', '.')).stripTrailingZeros();
        } catch (NumberFormatException | NullPointerException e) {
            throw new BadRequestException("Предоплата: число от 0 до 100");
        }
        if (v.scale() > 2 || v.signum() < 0 || v.compareTo(HUNDRED) > 0) {
            throw new BadRequestException("Предоплата: число от 0 до 100");
        }
        return v.setScale(2);
    }

    public static LocalDate date(String raw, String label, LocalDate today) {
        LocalDate d;
        try {
            d = LocalDate.parse(raw.strip());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BadRequestException(label + ": дата в виде ГГГГ-ММ-ДД");
        }
        return date(d, label, today);
    }

    /** Отмечаются факты, не планы: дата не позже сегодняшней по часам рынка. */
    public static LocalDate date(LocalDate d, String label, LocalDate today) {
        if (d == null) throw new BadRequestException(label + ": укажите дату");
        if (d.isAfter(today)) throw new BadRequestException(label + ": дата ещё не наступила — отмечаются только факты");
        return d;
    }

    /** «Сегодня» по часам рынка: прод живёт в UTC, а у Уральска уже завтра с 19:00 UTC. */
    public static LocalDate today(Clock clock, ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }

    public static ContractKind kind(String raw) {
        try {
            return ContractKind.valueOf(raw.strip());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Вид — договор, счёт или без договора");
        }
    }

    public static String currency(String raw) {
        String c = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!CURRENCIES.contains(c)) throw new BadRequestException("Валюта — тг, руб., $, € или юань");
        return c;
    }

    public static boolean bool(String raw) {
        if (raw == null || "false".equals(raw)) return false;
        if ("true".equals(raw)) return true;
        throw new BadRequestException("Нужно true или false");
    }

    /** Пусто — null; без пробелов по краям; длиннее max — 400. */
    public static String text(String raw, int max, String label) {
        String v = raw == null ? null : raw.strip();
        if (v == null || v.isEmpty()) return null;
        if (v.length() > max) throw new BadRequestException(label + ": не больше " + max + " символов");
        return v;
    }
}
