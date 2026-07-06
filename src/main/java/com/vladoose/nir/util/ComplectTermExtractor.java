package com.vladoose.nir.util;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Вынимает имя родительского аппарата (бренд) из названия+ТЗ аксессуарного лота — для поиска
 * по комплектности. В отличие от матчинга компонентов здесь generic-слова изделия (электрод,
 * силиконовый, размер…) выбрасываются, чтобы остался отличительный проприетарный токен («Элэскулап»).
 */
public final class ComplectTermExtractor {

    // закавыченная фраза в любых кавычках — сильнейший кандидат на имя аппарата
    private static final Pattern QUOTED = Pattern.compile("[«\"„']([\\p{L}\\p{Nd} .\\-]{3,40}?)[»\"'']");
    // «(для|к) аппарат.../систем... <Бренд>» → следующее слово с заглавной
    private static final Pattern AFTER_KW = Pattern.compile(
            "(?:аппарат\\p{L}*|систем\\p{L}*|прибор\\p{L}*)\\s+([\\p{Lu}][\\p{L}\\-]{2,})");

    // generic-слова изделия и канцелярит: не могут быть именем аппарата
    private static final Set<String> GENERIC = Set.of(
            "электрод", "электроды", "пластина", "пластины", "пластинка", "пластинки",
            "резиновый", "резиновые", "резиновая", "силиконовый", "силиконовые", "силиконовая",
            "электропроводящий", "электропроводящие", "токопроводящий", "токопроводящие",
            "терапевтический", "терапевтические", "размер", "размеры", "электрофорез", "электрофореза",
            "аппарат", "аппарата", "аппаратный", "система", "системы", "прибор", "прибора",
            "медицинский", "медицинская", "изделие", "изделия", "комплект", "набор",
            "для", "к", "и", "или", "с", "со", "по", "на", "мм", "см");

    private ComplectTermExtractor() {}

    public static String extract(String equipName, String spec) {
        String text = (equipName == null ? "" : equipName) + " " + (spec == null ? "" : spec);
        if (text.isBlank()) return null;

        Matcher q = QUOTED.matcher(text);
        if (q.find()) {
            String cand = q.group(1).trim();
            if (isDistinctive(cand)) return cand;
        }
        Matcher kw = AFTER_KW.matcher(text);
        if (kw.find()) {
            String cand = kw.group(1).trim();
            if (isDistinctive(cand)) return cand;
        }
        // иначе — самый длинный отличительный (не-generic) токен с буквами
        String best = null;
        for (String raw : text.split("[^\\p{L}\\-]+")) {
            String t = raw.replaceAll("^-+|-+$", "");
            if (t.length() < 4) continue;
            if (GENERIC.contains(t.toLowerCase())) continue;
            if (best == null || t.length() > best.length()) best = t;
        }
        return best;
    }

    private static boolean isDistinctive(String s) {
        for (String w : s.toLowerCase().split("\\s+")) {
            if (!GENERIC.contains(w) && w.length() >= 3) return true;
        }
        return false;
    }
}
