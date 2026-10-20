package com.vladoose.nir.service.productwatch.fit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Числа с условием и единицей в нормализованном тексте (спека §5.2) — общий разбор для требований ТЗ и характеристик
 * товара. Числа — по модулю (решение 4 плана): «−80 кПа» = 80.
 */
public final class SpecNumbers {

    public record Hit(SpecCmp cmp, double lo, double hi, SpecUnit unit, List<Double> dims, int start, int end) {}

    private static final String NUM = "(\\d{1,3}(?:[ \\u00a0]\\d{3})+|\\d+)(?:[.,](\\d+))?";
    /** Габариты: 2–3 числа через «х / x / × / *»; через «/» (карточка РУ: «295/295/100 мм») — только три и с единицей длины. */
    private static final Pattern DIMS = Pattern.compile(NUM + "\\s*[хx×*/]\\s*" + NUM + "(?:\\s*[хx×*/]\\s*" + NUM + ")?");
    /** Диапазон: «от A до B», «A–B», «A - B», «A...B», «A~B». */
    private static final Pattern RANGE = Pattern.compile("(?:от\\s*)?[-−–]?\\s*" + NUM
            + "\\s*(?:до|[-−–—~]|\\.\\.\\.?|…)\\s*(?:до\\s*)?[-−–]?\\s*" + NUM);
    private static final Pattern PLUS_MINUS = Pattern.compile(NUM + "\\s*±\\s*" + NUM);
    private static final Pattern SINGLE = Pattern.compile("[-−–]?\\s*" + NUM);
    /** Счётное без единицы: «… - 1», «…: 12» в конце пункта. */
    private static final Pattern TRAILING_COUNT = Pattern.compile("(?:\\s[-–—]|:)\\s*(\\d{1,3})\\s*\\.?\\s*$");

    private SpecNumbers() {}

    public static List<Hit> find(String t) {
        List<Hit> out = new ArrayList<>();
        if (t == null || t.isBlank()) return out;
        boolean[] used = new boolean[t.length()];
        // 1) габариты
        Matcher d = DIMS.matcher(t);
        while (d.find()) {
            SpecUnit.Hit u = SpecUnit.parseAt(t, d.end());
            List<Double> dims = new ArrayList<>();
            dims.add(num(d.group(1), d.group(2)));
            dims.add(num(d.group(3), d.group(4)));
            if (d.group(5) != null) dims.add(num(d.group(5), d.group(6)));
            if (u == null && d.group(5) == null) continue;   // «ОБН 2×30» — модель, не габариты: без единицы — только три числа
            if (t.substring(d.start(), d.end()).contains("/") && (u == null || d.group(5) == null)) continue;   // «50/60 Гц», дата
            SpecUnit unit = u == null ? SpecUnit.MM : u.unit();
            if (!unit.family().equals("длина")) continue;
            dims.replaceAll(unit::toBase);
            dims.sort((a, b) -> Double.compare(b, a));
            int start = cmpStart(t, d.start());
            SpecCmp cmp = cmpBefore(t, d.start());
            out.add(new Hit(cmp == SpecCmp.UPTO ? SpecCmp.MAX : cmp, dims.get(dims.size() - 1), dims.get(0), unit, List.copyOf(dims),
                    start, u == null ? d.end() : u.end()));
            mark(used, d.start(), u == null ? d.end() : u.end());
        }
        // 2) «A±D»
        Matcher pm = PLUS_MINUS.matcher(t);
        while (pm.find()) {
            if (used[pm.start()]) continue;
            SpecUnit.Hit u = SpecUnit.parseAt(t, pm.end());
            if (u == null) continue;
            double c = num(pm.group(1), pm.group(2)), dv = num(pm.group(3), pm.group(4));
            out.add(new Hit(SpecCmp.RANGE, u.unit().toBase(c - dv), u.unit().toBase(c + dv), u.unit(), List.of(),
                    pm.start(), u.end()));
            mark(used, pm.start(), u.end());
        }
        // 3) диапазоны
        Matcher r = RANGE.matcher(t);
        while (r.find()) {
            if (used[r.start()] || used[Math.max(r.start(), r.end() - 1)]) continue;
            SpecUnit.Hit u = SpecUnit.parseAt(t, r.end());
            if (u == null) continue;
            double a = num(r.group(1), r.group(2)), b = num(r.group(3), r.group(4));
            boolean dash = !t.substring(r.end(2) >= 0 ? r.end(2) : r.end(1), r.start(3)).contains("до");
            // «исполнение 3 - 1шт», «модель 674 - 1 шт»: тире перед количеством — не диапазон
            if (dash && (a > b || u.unit() == SpecUnit.PCS)) continue;
            if (a > b) { double x = a; a = b; b = x; }
            out.add(new Hit(SpecCmp.RANGE, u.unit().toBase(a), u.unit().toBase(b), u.unit(), List.of(), cmpStart(t, r.start()), u.end()));
            mark(used, r.start(), u.end());
        }
        // 4) одиночные числа с единицей
        Matcher s = SINGLE.matcher(t);
        while (s.find()) {
            int numStart = s.start(1);
            if (used[numStart]) continue;
            if (numStart > 0 && Character.isLetterOrDigit(t.charAt(numStart - 1))) continue;   // «ЭК12Т», «SpO2»
            SpecUnit.Hit u = SpecUnit.parseAt(t, s.end());
            if (u == null) continue;
            double raw = num(s.group(1), s.group(2));
            if (u.unit() == SpecUnit.YEAR && raw >= 1990 && raw <= 2100) continue;   // «2025 года» — дата, не срок
            double v = u.unit().toBase(raw);
            SpecCmp cmp = cmpBefore(t, s.start());
            double lo = cmp == SpecCmp.MAX || cmp == SpecCmp.UPTO ? 0 : v;
            double hi = cmp == SpecCmp.MIN ? Double.POSITIVE_INFINITY : v;
            out.add(new Hit(cmp, cmp == SpecCmp.APPROX ? v : lo, cmp == SpecCmp.APPROX ? v : hi, u.unit(), List.of(),
                    cmpStart(t, s.start()), u.end()));
            mark(used, s.start(), u.end());
        }
        // 5) счётное в конце пункта без единицы
        Matcher c = TRAILING_COUNT.matcher(t);
        if (c.find() && !used[c.start(1)]) {
            double v = Double.parseDouble(c.group(1));
            out.add(new Hit(SpecCmp.APPROX, v, v, null, List.of(), c.start(), t.length()));
        }
        out.sort((x, y) -> Integer.compare(x.start(), y.start()));
        return out;
    }

    /** Слова условий; «не более» / «не менее» заменены метками до поиска — иначе «более» внутри них давало бы обратное. */
    private record CmpWord(String w, SpecCmp cmp) {}
    private static final List<CmpWord> CMP_WORDS = List.of(
            new CmpWord("‹eq›", SpecCmp.APPROX), new CmpWord("‹min›", SpecCmp.MIN), new CmpWord("минимум", SpecCmp.MIN), new CmpWord("мин.", SpecCmp.MIN),
            new CmpWord("≥", SpecCmp.MIN), new CmpWord(">=", SpecCmp.MIN), new CmpWord(" более ", SpecCmp.MIN),
            new CmpWord(" свыше ", SpecCmp.MIN), new CmpWord(" больше ", SpecCmp.MIN), new CmpWord(" от ", SpecCmp.MIN),
            new CmpWord("‹max›", SpecCmp.MAX), new CmpWord("максимум", SpecCmp.MAX), new CmpWord("макс.", SpecCmp.MAX),
            new CmpWord("≤", SpecCmp.MAX), new CmpWord("<=", SpecCmp.MAX), new CmpWord(" менее ", SpecCmp.MAX),
            new CmpWord(" меньше ", SpecCmp.MAX),
            new CmpWord("примерно", SpecCmp.APPROX), new CmpWord("около", SpecCmp.APPROX), new CmpWord("порядка", SpecCmp.APPROX),
            new CmpWord("~", SpecCmp.APPROX), new CmpWord(" до ", SpecCmp.UPTO));

    /** Голова перед числом: до 30 символов, но не дальше предыдущей «, » / «;» — условие соседнего числа не наше. */
    static String head(String t, int at) {
        String h = t.substring(Math.max(0, at - 30), at);
        int cut = Math.max(h.lastIndexOf(", "), h.lastIndexOf(';'));
        return cut >= 0 ? h.substring(cut + 1) : h;
    }

    /** Условие перед числом (спека §5.2); нет слова условия — точное значение (APPROX), как и шаблон портала «не менее и не более». */
    static SpecCmp cmpBefore(String t, int at) {
        String h = (" " + head(t, at).toLowerCase(Locale.ROOT) + " ")
                .replace("не менее и не более", "‹eq›")
                .replace("не менее", "‹min›").replace("не ниже", "‹min›").replace("не меньше", "‹min›")
                .replace("не более", "‹max›").replace("не выше", "‹max›").replace("не больше", "‹max›");
        int best = -1;
        SpecCmp cmp = SpecCmp.APPROX;
        for (CmpWord w : CMP_WORDS) {
            int i = h.lastIndexOf(w.w());
            if (i > best) { best = i; cmp = w.cmp(); }
        }
        return cmp;
    }

    /** Начало условия перед числом (для текста требования): само число, если условия нет. */
    private static int cmpStart(String t, int at) {
        String h = head(t, at);
        int off = at - h.length();
        int best = at;
        for (String w : new String[]{"не менее", "не ниже", "не меньше", "не более", "не выше", "не больше", "примерно", "около", "от ", "до "}) {
            int i = h.lastIndexOf(w);
            if (i >= 0 && off + i < best && at - (off + i) <= 14) best = off + i;
        }
        int eq = h.lastIndexOf("не менее и не более");   // шаблон портала — в тексте требования целиком
        if (eq >= 0 && off + eq < best && at - (off + eq) <= 24) best = off + eq;
        return best;
    }

    private static double num(String whole, String frac) {
        String w = whole.replace(" ", "").replace(" ", "");
        return Double.parseDouble(frac == null ? w : w + "." + frac);
    }

    private static void mark(boolean[] used, int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(used.length, to); i++) used[i] = true;
    }
}
