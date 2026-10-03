package com.vladoose.nir.service.offer;

import com.vladoose.nir.entity.OfferColumn;
import com.vladoose.nir.entity.OfferTerm;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.util.DocFormat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Проверки колонок, условий и ставок — одни и те же для реквизитов рынка и для самого КП. Пустой элемент списка (null в
 * JSON) — 400: дальше по нему упал бы сборщик документа (500).
 */
public final class OfferSettingsValidator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private OfferSettingsValidator() {}

    public static void columns(List<OfferColumn> columns) {
        if (columns == null || columns.isEmpty()) throw new BadRequestException("Колонка «Наименование» обязательна");
        Set<OfferColumnKey> seen = new HashSet<>();
        for (OfferColumn c : columns) {
            if (c == null) throw new BadRequestException("Пустая колонка в списке колонок таблицы");
            OfferColumnKey key = OfferColumnKey.parse(c.getKey());
            if (!seen.add(key)) throw new BadRequestException("Колонка повторяется: " + key.defaultLabel(true));
            if (c.getLabel() != null && c.getLabel().length() > 120) {
                throw new BadRequestException("Подпись колонки длиннее 120 символов");
            }
        }
        if (!seen.contains(OfferColumnKey.NAME)) throw new BadRequestException("Колонка «Наименование» обязательна");
    }

    /** Пустое значение допустимо (оператор ещё печатает) — в документ такое условие не попадёт. */
    public static void terms(List<OfferTerm> terms) {
        if (terms == null) throw new BadRequestException("Список условий не передан");
        if (terms.size() > 30) throw new BadRequestException("Условий больше 30");
        for (OfferTerm t : terms) {
            if (t == null) throw new BadRequestException("Пустое условие в списке условий");
            if ((t.getLabel() != null && t.getLabel().length() > 300)
                    || (t.getValue() != null && t.getValue().length() > 2000)) {
                throw new BadRequestException("Условие слишком длинное");
            }
        }
    }

    public static void vatRates(List<BigDecimal> rates) {
        if (rates == null || rates.isEmpty()) throw new BadRequestException("Нужна хотя бы одна ставка НДС");
        if (rates.size() > 10) throw new BadRequestException("Ставок НДС больше 10");
        List<BigDecimal> seen = new ArrayList<>();
        for (BigDecimal r : rates) {
            if (!isVatRate(r)) throw new BadRequestException("Ставка НДС должна быть не меньше 0% и меньше 100%");
            if (containsRate(seen, r)) throw new BadRequestException("Ставка повторяется: " + DocFormat.rate(r));
            seen.add(r);
        }
    }

    /**
     * Ставка НДС — от 0 и меньше 100 %, null — «Без НДС». Расчёт делит на 100 + ставка: −100 уронило бы его делением на
     * ноль, а 100 % и больше НДС не бывает.
     */
    public static boolean isVatRate(BigDecimal rate) {
        return rate == null || (rate.signum() >= 0 && rate.compareTo(HUNDRED) < 0);
    }

    /** null в списке = «Без НДС»; сравнение по значению (5 == 5.00). */
    public static boolean containsRate(List<BigDecimal> rates, BigDecimal rate) {
        if (rates == null) return false;
        for (BigDecimal r : rates) {
            if (r == null ? rate == null : rate != null && r.compareTo(rate) == 0) return true;
        }
        return false;
    }
}
