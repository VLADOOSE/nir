package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyTable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Значения строки таблицы по ролям колонок (спека 2026-10-15 §3.2–3.4). Цена — из колонки цены с наибольшим приоритетом
 * («со скидкой» > «с НДС» > просто цена > «без НДС»); ячейка цены ПУСТАЯ — цена = сумма / количество (количества
 * нет — 1), с пометкой priceComputed; в ячейке цены текст («нет», «по запросу») или 0 — priceRefused, из суммы не
 * считается. Колонок цены нет вовсе — цена из суммы.
 */
public record ReplyRowValues(int fileRow, String name, String article, String brand, String number,
                             MoneyText.Qty quantity, String unit, BigDecimal price, boolean priceComputed,
                             boolean priceRefused, BigDecimal sum, String vat, String currency, String note) {

    /** Наименование, артикул и бренд — то, что сравнивается с позицией. */
    public String matchText() {
        return String.join(" ", nonBlank(name, article, brand));
    }

    /** Для людей: наименование, артикул (если его нет в наименовании) и бренд через «·». */
    public String displayText() {
        List<String> parts = new ArrayList<>();
        if (name != null && !name.isBlank()) parts.add(name);
        if (article != null && !article.isBlank() && (name == null || !name.contains(article))) parts.add(article);
        if (brand != null && !brand.isBlank()) parts.add(brand);
        return String.join(" · ", parts);
    }

    /** Строка участвует в сопоставлении: есть цена, явный отказ или примечание. */
    public boolean participates() {
        return (price != null || priceRefused || (note != null && !note.isBlank())) && !matchText().isBlank();
    }

    public static List<ReplyRowValues> of(ReplyTable t) {
        Map<LineField, List<Integer>> by = new EnumMap<>(LineField.class);
        for (ReplyTable.Column c : t.columns()) by.computeIfAbsent(c.role(), k -> new ArrayList<>()).add(c.index());
        List<Integer> prices = sorted(by.getOrDefault(LineField.PRICE, List.of()), t);
        List<Integer> sums = sorted(by.getOrDefault(LineField.SUM, List.of()), t);
        List<ReplyRowValues> out = new ArrayList<>(t.rows().size());
        for (ReplyTable.Row r : t.rows()) {
            List<String> cells = r.cells();
            String name = join(" ", cells, by.get(LineField.NAME));
            MoneyText.Qty q = MoneyText.quantity(first(cells, by.get(LineField.QUANTITY)));
            String unit = first(cells, by.get(LineField.UNIT));
            if ((unit == null || unit.isBlank()) && q != null) unit = q.unit();
            String sumCell = sums.isEmpty() ? null : cells.get(sums.get(0));
            BigDecimal sum = MoneyText.amount(sumCell);
            if (sum != null && sum.signum() == 0) sum = null;
            String priceCell = prices.isEmpty() ? null : cells.get(prices.get(0));
            BigDecimal price = null;
            boolean computed = false, refused = false;
            if (priceCell != null && !priceCell.isBlank()) {
                price = MoneyText.amount(priceCell);
                if (price == null || price.signum() == 0) {
                    price = null;
                    refused = true;
                }
            } else if (sum != null) {
                BigDecimal n = q == null || q.value().signum() <= 0 ? BigDecimal.ONE : q.value();
                price = sum.divide(n, 2, RoundingMode.HALF_UP);
                computed = true;
            }
            String cur = MoneyText.currency(first(cells, by.get(LineField.CURRENCY)));
            if (cur == null) cur = MoneyText.currency(priceCell);
            if (cur == null) cur = MoneyText.currency(sumCell);
            if (cur == null) cur = t.currency();
            out.add(new ReplyRowValues(r.fileRow(), blankToNull(name), blankToNull(first(cells, by.get(LineField.ARTICLE))),
                    blankToNull(first(cells, by.get(LineField.MANUFACT))), blankToNull(first(cells, by.get(LineField.NUMBER))),
                    q, blankToNull(unit), price, computed, refused, sum, blankToNull(first(cells, by.get(LineField.VAT))),
                    cur, blankToNull(join("; ", cells, by.get(LineField.NOTE)))));
        }
        return out;
    }

    /** Приоритет колонки цены / суммы по заголовку: «со скидкой», «итог», «с учётом» — 3; «с НДС» — 2; прочее — 1; «без НДС» — 0. */
    static int priority(String header) {
        String h = header == null ? "" : header.toLowerCase(Locale.ROOT);
        if (h.contains("скидк") || h.contains("итог") || h.contains("с учет") || h.contains("с учёт")) return 3;
        if (h.contains("без ндс")) return 0;
        if (h.contains("ндс")) return 2;
        return 1;
    }

    private static List<Integer> sorted(List<Integer> idx, ReplyTable t) {
        List<Integer> out = new ArrayList<>(idx);
        out.sort(Comparator.comparingInt((Integer i) -> -priority(t.columns().get(i).header())));
        return out;
    }

    private static String first(List<String> cells, List<Integer> idx) {
        if (idx == null) return null;
        for (int i : idx) if (i < cells.size() && !cells.get(i).isBlank()) return cells.get(i);
        return null;
    }

    private static String join(String sep, List<String> cells, List<Integer> idx) {
        if (idx == null) return null;
        List<String> parts = new ArrayList<>();
        for (int i : idx) if (i < cells.size() && !cells.get(i).isBlank()) parts.add(cells.get(i));
        return String.join(sep, parts);
    }

    private static List<String> nonBlank(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) out.add(p);
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
