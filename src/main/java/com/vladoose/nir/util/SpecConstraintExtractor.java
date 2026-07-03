package com.vladoose.nir.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Извлекает из текста спецификации лота ВЕРХНИЕ ограничения габаритов и веса.
 * Берём только «не более/до/≤/максимум …» и значения без квалификатора;
 * «не менее/от/минимум» — нижние границы, игнорируются.
 * MVP: триплет A×B×C с ключевым словом (габарит*, размер*) + вес/масса. Best-effort: ничего не нашли — пустой результат.
 */
public final class SpecConstraintExtractor {

    public record SpecConstraints(Integer maxLengthMm, Integer maxWidthMm, Integer maxHeightMm,
                                  BigDecimal maxWeightKg, List<String> snippets) {
        public boolean isEmpty() {
            return maxLengthMm == null && maxWidthMm == null && maxHeightMm == null && maxWeightKg == null;
        }
    }

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final String NUM = "(\\d+(?:[.,]\\d+)?)";
    private static final String X = "\\s*[xх×*]\\s*";

    /** «габариты … 1200х800х1300 мм»: g1 — зазор (для проверки на «не менее»), g2..g4 — числа, g5 — единица. */
    private static final Pattern TRIPLE = Pattern.compile(
            "(?:габарит\\w*|размер\\w*)([^\\n]{0,60}?)" + NUM + X + NUM + X + NUM + "\\s*(мм|см|м)\\b", FLAGS);

    /** «вес/масса … 45 кг»: g1 — зазор, g2 — число, g3 — единица. Зазор без цифр — диапазоны («от 30 до 45») не матчатся. */
    private static final Pattern WEIGHT = Pattern.compile(
            "(?:вес|масса)([^\\n\\d]{0,40}?)" + NUM + "\\s*(кг|г)\\b", FLAGS);

    private static final Pattern LOWER_BOUND = Pattern.compile(
            "не\\s+менее|не\\s+ниже|минимум|\\bот\\b", FLAGS);

    private SpecConstraintExtractor() {}

    public static SpecConstraints extract(String spec) {
        List<String> snippets = new ArrayList<>();
        Integer len = null, wid = null, hei = null;
        BigDecimal weight = null;
        if (spec != null && !spec.isBlank()) {
            Matcher t = TRIPLE.matcher(spec);
            while (t.find()) {
                if (LOWER_BOUND.matcher(t.group(1)).find()) continue;
                double k = unitToMm(t.group(5));
                len = toMm(t.group(2), k);
                wid = toMm(t.group(3), k);
                hei = toMm(t.group(4), k);
                snippets.add(spec.substring(t.start(), t.end()).trim());
                break; // первый валидный триплет
            }
            Matcher w = WEIGHT.matcher(spec);
            while (w.find()) {
                if (LOWER_BOUND.matcher(w.group(1)).find()) continue;
                BigDecimal v = new BigDecimal(w.group(2).replace(',', '.'));
                if ("г".equalsIgnoreCase(w.group(3))) {
                    v = v.divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
                }
                weight = v;
                snippets.add(spec.substring(w.start(), w.end()).trim());
                break;
            }
        }
        return new SpecConstraints(len, wid, hei, weight, snippets);
    }

    private static double unitToMm(String unit) {
        return switch (unit.toLowerCase()) {
            case "м" -> 1000.0;
            case "см" -> 10.0;
            default -> 1.0; // мм
        };
    }

    private static Integer toMm(String num, double k) {
        return (int) Math.round(Double.parseDouble(num.replace(',', '.')) * k);
    }
}
