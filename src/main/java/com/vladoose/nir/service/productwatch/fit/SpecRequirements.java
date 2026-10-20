package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.service.productwatch.ProductText;
import com.vladoose.nir.util.TechSpecExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Требования из ТЗ лота (спека §5): русская часть, раздел характеристик, строки PDF склеены, пункты — через «;»,
 * с новой строки, «-» / «•», конец предложения; «не требования» (назначение, поставка, гарантия, документы) — мимо.
 */
public final class SpecRequirements {

    /** Больше — не сверяем (ТЗ томографа на 30 тыс. знаков): берём первые. */
    public static final int MAX = 150;

    private static final Pattern ANCHOR = Pattern.compile("характеристики\\s+закупаемых\\s+товаров[^\\n:]*:?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SPLIT = Pattern.compile("[;\\n•]|(?<=[\\p{Ll}\\d)»])\\.\\s+(?=\\p{Lu})");
    /** Номер или значок пункта списка: «1.», «1.2.», «2)», «-», «•» — но не число-содержание («5 электродов»). */
    private static final Pattern LIST_MARK = Pattern.compile("^(?:[-–—•*·]+\\s*|\\d{1,2}(?:\\.\\d{1,2})*[.)]\\s*)+(?=\\p{L})");
    /** Номер строки таблицы «2 Устройство …»: число и слово с заглавной, если это слово — не единица («400 Вт» — значение). */
    private static final Pattern ROW_NUMBER = Pattern.compile("^\\d{1,3}\\s+(?=\\p{Lu})");
    private static final Pattern HEADER = Pattern.compile("^[^:]{0,60}(?:характеристик|параметр|требовани|комплектац|состав)[^:]{0,40}:\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * Признаки «не требования» (спека §5.4): пункт обрезается по первому из них — абзац без «;» («Масса: 3 кг … Поставщик
     * должен представить…») не теряет значения перед ним; пункт, который с них начинается, — мимо целиком.
     */
    private static final String[] SKIP = {"предназнач", "применя", "область", "используется", "позволяет", "поможет",
            "избавит", "сэкономит", "наименование", "номер закупки", "номер лота", "количество:", "единица измерения",
            "мест поставки", "места поставки", "место поставки", "срок поставки", "гаранти", "регистрационн", "регистрации в",
            "регистрация в", "зарегистрир", "сертификат", "декларац", "паспорт", "инструкц", "обучени", "монтаж",
            "ввод в эксплуатац", "поставщик", "доставк", "год выпуска", "назначени", "документов", "документы", "документац"};
    /** Слова признака (спека §5.3): пункт без числа, но с ними или «параметр — значение». */
    private static final String[] FEATURE_WORDS = {"наличие", "возможност", "режим", "функци", "тип", "систем", "защит",
            "индикац", "регулиров", "работа от", "комплект", "конструкц", "материал", "цвет", "дисплей", "экран", "память"};

    private SpecRequirements() {}

    /** Текст для разбора: русская часть, после «характеристики закупаемых товаров» (если есть), строки склеены. */
    public static String body(String requiredSpec) {
        if (requiredSpec == null || requiredSpec.isBlank()) return "";
        String ru = TechSpecExtractor.russianSection(requiredSpec);
        String text = ru == null ? requiredSpec : ru;
        Matcher m = ANCHOR.matcher(text);
        return reflow(m.find() ? text.substring(m.end()) : text);
    }

    /**
     * Строка без «;» / «:» / «.» в конце, а следующая — со строчной, цифры или единицы, — продолжение; после «:» —
     * продолжение строка с цифры (значение параметра строкой ниже).
     */
    static String reflow(String text) {
        StringBuilder out = new StringBuilder();
        for (String raw : text.replace('\t', ' ').replace('\r', '\n').split("\n")) {
            String l = raw.replaceAll(" {2,}", " ").strip();
            if (l.isEmpty()) continue;
            boolean cont = out.length() > 0 && (!endsClause(out) && startsContinuation(l)
                    || out.charAt(out.length() - 1) == ':' && Character.isDigit(l.charAt(0)) && markerEnd(l) == 0);   // «Мощность:» ↵ «400 Вт»
            out.append(cont ? " " : (out.length() > 0 ? "\n" : "")).append(l);
        }
        return out.toString();
    }

    private static boolean endsClause(StringBuilder s) {
        char c = s.charAt(s.length() - 1);
        return c == ';' || c == ':' || c == '.';
    }

    private static boolean startsContinuation(String l) {
        char c = l.charAt(0);
        if (Character.isDigit(c) || c == '(' || c == ',') return true;
        if (!Character.isLetter(c) || !Character.isLowerCase(c)) return false;
        return true;
    }

    public static List<SpecRequirement> extract(String body) {
        List<SpecRequirement> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;
        for (String raw : SPLIT.split(body)) {
            String clause = raw.strip();
            clause = clause.substring(markerEnd(clause)).strip();
            clause = HEADER.matcher(clause).replaceFirst("");
            int stop = skipAt(ProductText.normalize(clause));
            if (stop >= 0) clause = clause.substring(0, stop).strip();
            if (clause.length() < 3) continue;
            String norm = ProductText.normalize(clause);
            List<SpecNumbers.Hit> hits = SpecNumbers.find(norm);
            if (hits.isEmpty()) {
                if (feature(norm)) out.add(new SpecRequirement(cut(clause, 160), cut(norm, 120), SpecRequirement.Kind.FEATURE,
                        SpecCmp.APPROX, 0, 0, null, List.of()));
            } else {
                int prevEnd = 0;
                String lastName = name(norm.substring(0, hits.get(0).start()));
                for (SpecNumbers.Hit h : hits) {
                    String own = name(norm.substring(Math.min(prevEnd, h.start()), h.start()));
                    String nm = own.length() >= 3 ? own : lastName;
                    prevEnd = h.end();
                    if (nm.length() < 3) continue;   // число без параметра («-1 ШТ», строка «400 Вт» без заголовка) — сверять не с чем
                    lastName = nm;
                    String value = clause.substring(Math.min(h.start(), clause.length()), Math.min(h.end(), clause.length()));
                    String text = cut((nm + " " + value).replaceAll("\\s{2,}", " ").strip(), 160);
                    SpecRequirement.Kind kind = !h.dims().isEmpty() ? SpecRequirement.Kind.DIMS
                            : h.unit() == null ? SpecRequirement.Kind.COUNT : SpecRequirement.Kind.NUMBER;
                    out.add(new SpecRequirement(text, nm, kind, h.cmp(), h.lo(), h.hi(), h.unit(), h.dims()));
                }
            }
            if (out.size() >= MAX) return out.subList(0, MAX);
        }
        return out;
    }

    /** Позиция первого признака «не требования» в нормализованном пункте; -1 — нет. */
    private static int skipAt(String norm) {
        int at = -1;
        for (String w : SKIP) {
            int i = norm.indexOf(w);
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        return at;
    }

    /** Длина номера / значка пункта или номера строки таблицы в начале строки; 0 — нет. */
    static int markerEnd(String line) {
        Matcher m = LIST_MARK.matcher(line);
        if (m.find()) return m.end();
        Matcher row = ROW_NUMBER.matcher(line);
        if (row.find() && SpecUnit.parseAt(ProductText.normalize(line), row.end()) == null) return row.end();
        return 0;
    }

    /** Признак: слово признака или «параметр — значение», и не меньше двух значимых слов. */
    private static boolean feature(String norm) {
        long significant = ProductText.words(norm).stream().filter(ProductText::significant).count();
        if (significant < 2) return false;
        for (String w : FEATURE_WORDS) if (norm.contains(w)) return true;
        return norm.matches(".{3,60}\\s[-–—:]\\s.+");
    }

    private static final Pattern NAME_TAIL = Pattern.compile("(?:[\\s:\\-–—,(±/=]+|(?<![\\p{L}])(?:не менее|не более|не ниже|не выше"
            + "|не меньше|не больше|примерно|около|от|до|и|или))$");

    /** Имя параметра: хвост текста перед числом без условий («не менее и не более»), связок и знаков, ≤ 80. */
    private static String name(String head) {
        String n = head.replaceAll("^(?:[,;.\\s()/]|\\d{1,2}[.)](?=\\s))+", "");
        for (String prev = null; !n.equals(prev); ) {
            prev = n;
            n = NAME_TAIL.matcher(n).replaceAll("");
        }
        return cut(n.strip().toLowerCase(Locale.ROOT), 80);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
