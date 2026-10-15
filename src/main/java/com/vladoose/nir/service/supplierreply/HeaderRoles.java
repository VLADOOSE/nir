package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;

import java.util.Locale;
import java.util.Map;

/**
 * Роль колонки по заголовку (спека 2026-10-15 §3.2): сначала обучаемый словарь header_synonym (ключ — как его пишет
 * PrivateRequestImportService.learn: обрезка и нижний регистр), иначе подстроки В ПОРЯДКЕ — первое совпадение решает
 * (решение плана 13: «Цена (в т.ч. НДС)» — цена, «Сумма НДС» — НДС, «Наименование (описание), единица измерения» —
 * наименование, «Количество товара» — количество, «Штук в упаковке» — примечание, «Страна производства» — не бренд).
 */
public final class HeaderRoles {

    private HeaderRoles() {}

    public static String key(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT);
    }

    public static LineField role(String header, Map<String, LineField> learned) {
        String k = key(header);
        if (k.isEmpty()) return LineField.IGNORE;
        LineField l = learned.get(k);
        if (l != null) return l;
        String c = k.replaceAll("\\s+", " ").replace('ё', 'е');
        l = learned.get(c);
        return l != null ? l : heuristic(c);
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
