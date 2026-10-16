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
import java.util.regex.Pattern;

/**
 * Значения строки таблицы по ролям колонок (спека 2026-10-15 §3.2–3.4). Цена — из колонки цены с наибольшим приоритетом
 * («со скидкой» > «с НДС» > просто цена > «без НДС»; «без / до скидки» — ниже такой же цены без пометки); ячейка цены
 * ПУСТАЯ — цена = сумма / количество (количества нет — 1), с пометкой priceComputed; в ячейке цены текст («нет», «по
 * запросу») или 0 — priceRefused, из суммы не считается. Колонок цены нет вовсе — цена из суммы. Цена не «со скидкой»
 * (только «без скидки» — КП ФОТЕК 07.10.2026, или просто «Цена» формы 1С), а рядом «Сумма со скидкой» — цена = сумма со
 * скидкой / количество, тоже с пометкой priceComputed; только при распознанном количестве и не выше цены больше чем на
 * НДС (×1,25: иначе количество не в тех единицах). Сумма строки — с той же ставкой НДС, что у колонки цены (сверка
 * «сумма ≠ цена × кол-во»); цена из суммы — с той суммой, из которой посчитана.
 */
public record ReplyRowValues(int fileRow, String name, String article, String brand, String number,
                             MoneyText.Qty quantity, String unit, BigDecimal price, boolean priceComputed,
                             boolean priceRefused, BigDecimal sum, String vat, String currency, String note) {

    /** «со / с / после / с учётом скидки» отдельным словом («ндс скидки» — не то). */
    private static final Pattern WITH_DISCOUNT = Pattern.compile("(?U)\\b(со|с|после|учетом)\\s+скидк");
    /** Цена из «Суммы со скидкой» — не выше цены без скидки больше чем на НДС (до 25 %). */
    private static final BigDecimal ABOVE_PRICE_MAX = new BigDecimal("1.25");

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
        String priceHeader = prices.isEmpty() ? null : lower(t.columns().get(prices.get(0)).header());
        // сумма строки — с той же ставкой НДС, что у колонки цены: иначе «сумма ≠ цена × кол-во» на каждой строке
        List<Integer> rowSums = priceHeader == null ? sums : sameVatFirst(sums, vat(priceHeader), t);
        boolean discountRule = priceHeader != null && !withDiscount(priceHeader);
        List<ReplyRowValues> out = new ArrayList<>(t.rows().size());
        for (ReplyTable.Row r : t.rows()) {
            List<String> cells = r.cells();
            String name = join(" ", cells, by.get(LineField.NAME));
            MoneyText.Qty q = MoneyText.quantity(first(cells, by.get(LineField.QUANTITY)));
            String unit = first(cells, by.get(LineField.UNIT));
            if ((unit == null || unit.isBlank()) && q != null) unit = q.unit();
            String sumCell = rowSums.isEmpty() ? null : cells.get(rowSums.get(0));
            BigDecimal sum = MoneyText.amount(sumCell);
            if (sum != null && sum.signum() == 0) sum = null;
            String priceCell = prices.isEmpty() ? null : cells.get(prices.get(0));
            BigDecimal price = null;
            boolean computed = false, refused = false;
            boolean counted = q != null && q.value().signum() > 0;
            BigDecimal n = counted ? q.value() : BigDecimal.ONE;
            if (priceCell != null && !priceCell.isBlank()) {
                price = MoneyText.amount(priceCell);
                if (price == null || price.signum() == 0) {
                    price = null;
                    refused = true;
                } else if (discountRule && counted) {
                    // цена без скидки, а скидка — в «Сумме со скидкой» (КП ФОТЕК, форма 1С): цена за единицу — из неё
                    BigDecimal discounted = discountedSum(cells, sums, t);
                    BigDecimal each = discounted == null ? null : discounted.divide(n, 2, RoundingMode.HALF_UP);
                    // скидки нет («Доставка» — та же сумма) — без пометки; выше цены больше чем на НДС — не та единица
                    if (each != null && each.compareTo(price) != 0 && each.compareTo(price.multiply(ABOVE_PRICE_MAX)) <= 0) {
                        price = each;
                        computed = true;
                        sum = discounted;
                    }
                }
            } else if (sum != null) {
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

    /**
     * Приоритет колонки цены / суммы по заголовку: «со скидкой», «итог», «с учётом» — выше всех; дальше по НДС: «с НДС»
     * выше просто цены, та — выше «без НДС» (из двух «со скидкой» берётся «с НДС» — как в счёте поставщика); при равных
     * — без пометки «без / до скидки» выше неё (иначе «Цена без скидки, с НДС», стоя левее, перебивала бы «Цену, с
     * НДС»). Размер скидки («Скидка (сумма)», «Скидка, %») — ниже всех: это не цена и не сумма строки.
     */
    static int priority(String header) {
        String h = lower(header);
        if (discountAmount(h)) return -1;
        boolean without = withoutDiscount(h);
        boolean top = !without && (withDiscount(h) || h.contains("итог") || h.contains("с учет"));
        return (top ? 100 : 0) + 10 * vat(h) + (without ? 0 : 1);
    }

    /** НДС по заголовку: «без НДС» — 0, не сказано — 1, «с НДС» — 2. */
    private static int vat(String h) {
        return h.contains("без ндс") ? 0 : h.contains("ндс") ? 2 : 1;
    }

    /** «Со скидкой»: явное «со / с учётом / после скидки», а у цены — любое упоминание скидки, кроме «без / до скидки». */
    static boolean withDiscount(String h) {
        if (!h.contains("скидк") || withoutDiscount(h)) return false;
        return h.contains("цен") || WITH_DISCOUNT.matcher(h).find();
    }

    private static boolean withoutDiscount(String h) {
        return h.contains("без скидк") || h.contains("до скидк") || h.contains("без учета скидк");
    }

    /** Размер скидки («Скидка, %», «Скидка (сумма)», «Сумма скидки»): про скидку, но не цена и не сумма со скидкой. */
    private static boolean discountAmount(String h) {
        return h.contains("скидк") && !withoutDiscount(h) && !withDiscount(h);
    }

    /**
     * Первая непустая «Сумма со скидкой» строки (колонки уже по приоритету: с НДС раньше без НДС); нет — null. Только
     * явная сумма («сумма», «итого», «всего»): «Стоимость со скидкой» бывает и ценой за единицу — делить её на количество
     * нельзя.
     */
    private static BigDecimal discountedSum(List<String> cells, List<Integer> sums, ReplyTable t) {
        for (int i : sums) {
            String h = lower(t.columns().get(i).header());
            if (!(h.contains("сумм") || h.contains("итог") || h.contains("всего")) || !withDiscount(h)) continue;
            BigDecimal v = i < cells.size() ? MoneyText.amount(cells.get(i)) : null;
            if (v != null && v.signum() > 0) return v;
        }
        return null;
    }

    /** Колонки той же ставки НДС — первыми (порядок приоритета внутри сохраняется), остальные — за ними. */
    private static List<Integer> sameVatFirst(List<Integer> sums, int vat, ReplyTable t) {
        List<Integer> out = new ArrayList<>(sums.size());
        for (int i : sums) if (vat(lower(t.columns().get(i).header())) == vat) out.add(i);
        for (int i : sums) if (!out.contains(i)) out.add(i);
        return out;
    }

    private static String lower(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replace('ё', 'е');
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
