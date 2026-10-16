package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Роль колонки по заголовку (спека 2026-10-15 §3.2): сначала обучаемый словарь header_synonym (ключ — как его пишет
 * PrivateRequestImportService.learn: обрезка и нижний регистр), иначе подстроки В ПОРЯДКЕ — первое совпадение решает
 * (решение плана 13: «Цена (в т.ч. НДС)» — цена, «Сумма НДС» — НДС, «Наименование (описание), единица измерения» —
 * наименование, «Количество товара» — количество, «Штук в упаковке» — примечание, «Страна производства» — не бренд).
 */
public final class HeaderRoles {

    /** Мягкий перенос: U+00AD (.docx) и 0x1F — «необязательный дефис» Word 97 (.doc). */
    private static final Pattern SOFT_HYPHEN = Pattern.compile("[\\u00AD\\u001F]");
    /** Перенос внутри слова: дефис вплотную за буквой, дальше — пробелы (перевод строки в ячейке) и снова буква. */
    private static final Pattern WORD_BREAK = Pattern.compile("(?<=\\p{L})-\\s*(?=\\p{L})");

    private HeaderRoles() {}

    public static String key(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Роль: словарь, иначе правила. Вышло «Игнорировать» по правилам — ещё раз для заголовка без переноса внутри слова
     * («Коли-чество», «Скид- ка»): что узнаётся с дефисом («Кол-во»), так и остаётся.
     */
    public static LineField role(String header, Map<String, LineField> learned) {
        String k = key(header);
        if (k.isEmpty()) return LineField.IGNORE;
        LineField l = learnedRole(header, learned);
        if (l != null) return l;
        LineField h = heuristic(norm(k));
        if (h != LineField.IGNORE) return h;
        String joined = WORD_BREAK.matcher(SOFT_HYPHEN.matcher(k).replaceAll("")).replaceAll("");
        if (joined.equals(k)) return h;
        LineField lj = learnedRole(joined, learned);
        return lj != null ? lj : heuristic(norm(joined));
    }

    /** Роль из словаря оператора (как есть или со сжатыми пробелами и «ё» → «е»); нет в словаре — null. */
    static LineField learnedRole(String header, Map<String, LineField> learned) {
        String k = key(header);
        if (k.isEmpty()) return null;
        LineField l = learned.get(k);
        return l != null ? l : learned.get(norm(k));
    }

    static String norm(String key) {
        return key.replaceAll("\\s+", " ").replace('ё', 'е');
    }

    static LineField heuristic(String h) {
        if (has(h, "цена", "стоимость за ед", "стоимость ед", "стоимость за 1")) return LineField.PRICE;
        if (h.contains("сумма ндс")) return LineField.VAT;
        if (h.contains("сумма скидк")) return LineField.IGNORE;   // размер скидки — не сумма строки («Сумма со скидкой» — сумма)
        if (has(h, "сумма", "стоимость")) return LineField.SUM;
        if (h.contains("ндс")) return LineField.VAT;
        if (h.contains("наимен")) return LineField.NAME;
        if (h.contains("в упаков")) return LineField.NOTE;
        if (has(h, "кол-во", "количество", "кол.")) return LineField.QUANTITY;
        if (has(h, "ед. изм", "ед.изм", "единиц") || h.equals("ед") || h.equals("ед.")) return LineField.UNIT;
        if (has(h, "товар", "описание", "номенклат", "продукц", "комплектац")) return LineField.NAME;
        if (has(h, "артикул", "модель", "каталож")) return LineField.ARTICLE;
        if (h.contains("страна")) return LineField.IGNORE;
        if (has(h, "производ", "бренд", "марка")) return LineField.MANUFACT;
        if (h.contains("валют")) return LineField.CURRENCY;
        if (has(h, "примеч", "комментар", "наличие", "срок")) return LineField.NOTE;
        if (h.startsWith("№") || has(h, "п/п", "п.п") || h.equals("пп")) return LineField.NUMBER;
        return LineField.IGNORE;
    }

    private static boolean has(String h, String... parts) {
        for (String p : parts) if (h.contains(p)) return true;
        return false;
    }
}
