package com.vladoose.nir.integration;

import java.math.BigDecimal;

/**
 * Количество и сумма лота с площадки (B3) — общие правила обоих импортов.
 *
 * <p>Колонка {@code tender_lot.quantity} — целое, {@code max_cost} — {@code NUMERIC(15,2)} с CHECK &gt; 0.
 * Площадка же присылает и «0.5» (goszakup, Jackson молча делал из него 0 в {@code Integer}), и «2.5» / «0»
 * (СК-Фармация), и нулевую сумму. Прежде дробная часть отрезалась (заказ занижался), а ноль или
 * переполнение суммы роняли запись всего тендера. Теперь: не целое &gt; 0 — количество пусто, сырое значение
 * площадки остаётся строкой-пометкой в {@code requiredSpec}; не положительная или слишком длинная сумма — пусто.
 */
public final class ImportQuantity {

    private static final String NOTE_PREFIX = "Количество на площадке: ";

    private ImportQuantity() {}

    /** Целое &gt; 0 (в том числе «1.00») → int; дробное, ≤ 0, null, больше {@code Integer.MAX_VALUE} → null. */
    public static Integer wholePositiveOrNull(BigDecimal q) {
        if (q == null || q.signum() <= 0) return null;
        try {
            return q.intValueExact();
        } catch (ArithmeticException e) {   // дробная часть или переполнение int
            return null;
        }
    }

    /** «1 000.00», «1&nbsp;000», «2,5» → число; пробелы-тысячи (в том числе неразрывные) убираются, запятая → точка. Мусор/пусто → null. */
    public static BigDecimal parse(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[\\s\\u00A0\\u202F]", "").replace(',', '.');
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Строка-пометка с сырым значением площадки (запятая или точка — как на площадке). */
    public static String note(String raw) {
        return NOTE_PREFIX + raw.trim();
    }

    /** Сумма лота: null, ≤ 0 или целая часть длиннее 13 цифр (предел {@code NUMERIC(15,2)}) → null. */
    public static BigDecimal positiveMoneyOrNull(BigDecimal p) {
        if (p == null || p.signum() <= 0) return null;
        if (p.precision() - p.scale() > 13) return null;
        return p;
    }

    /** Пометка перед текстом; повторный вызов текст не меняет (пометка не двоится). */
    public static String withNote(String note, String text) {
        if (note == null) return text;
        if (text == null || text.isBlank()) return note;
        if (text.startsWith(note)) return text;
        return note + "\n" + text;
    }
}
