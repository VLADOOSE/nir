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

/** Проверки колонок, условий и ставок — одни и те же для реквизитов рынка и для самого КП. */
public final class OfferSettingsValidator {

    private OfferSettingsValidator() {}

    public static void columns(List<OfferColumn> columns) {
        if (columns == null || columns.isEmpty()) throw new BadRequestException("Колонка «Наименование» обязательна");
        Set<OfferColumnKey> seen = new HashSet<>();
        for (OfferColumn c : columns) {
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
            if (r != null && (r.signum() < 0 || r.compareTo(BigDecimal.valueOf(100)) > 0)) {
                throw new BadRequestException("Ставка НДС должна быть от 0 до 100%");
            }
            if (containsRate(seen, r)) throw new BadRequestException("Ставка повторяется: " + DocFormat.rate(r));
            seen.add(r);
        }
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
