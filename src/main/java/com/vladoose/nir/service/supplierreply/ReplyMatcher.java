package com.vladoose.nir.service.supplierreply;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Строки файла → позиции запроса (спека 2026-10-15 §3.3). Слова: нижний регистр, «ё» → «е», латинские двойники
 * кириллицы складываются; слово от 3 букв или с цифрами; совпадение — целиком или по первым 5 буквам. Вес слова —
 * редкость внутри файла, у кодов моделей («MSL24», «ЭК3ТЦ-3/6-04») — втрое. Оценка = 0,75 × доля веса слов позиции,
 * найденных в строке, + 0,25 × доля веса слов строки, найденных в позиции. Пары разбираются от лучшей; одна строка —
 * одной позиции. Подставить — оценка ≥ 0,60 и отрыв от второй кандидатуры ≥ 0,15 и у позиции, и у строки; иначе
 * подсказка (≥ 0,35). Разные числа у обеих сторон («№ 5» и «№ 2», «60 мм» и «40 мм») — оценка ×0,5: подставить
 * нельзя, подсказать можно (решение плана 16). Принцип проекта: лучше не найти, чем перепутать.
 */
public final class ReplyMatcher {

    public static final double AUTO_MIN = 0.60;
    public static final double AUTO_MARGIN = 0.15;
    public static final double SUGGEST_MIN = 0.35;
    static final double CODE_WEIGHT = 3.0;
    /** Не больше 0,5 < AUTO_MIN: пара с разными числами уверенной не бывает. */
    static final double CONFLICT_FACTOR = 0.5;
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    public enum Kind { AUTO, SUGGEST }

    public record Position(long itemId, int ordinal, String text, Integer quantity) {}

    public record Row(int fileRow, int order, String text, BigDecimal quantity) {}

    public record Match(long itemId, int fileRow, double score, Kind kind) {}

    private ReplyMatcher() {}

    public static List<Match> match(List<Position> positions, List<Row> rows) {
        if (positions.isEmpty() || rows.isEmpty()) return List.of();
        Weights w = Weights.of(rows);
        double[][] s = new double[positions.size()][rows.size()];
        record Pair(int p, int r, double score) {}
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            for (int j = 0; j < rows.size(); j++) {
                s[i][j] = pairScore(w, positions.get(i).text(), rows.get(j).text());
                if (s[i][j] >= SUGGEST_MIN) pairs.add(new Pair(i, j, s[i][j]));
            }
        }
        pairs.sort(Comparator.comparingDouble((Pair x) -> -x.score())
                .thenComparingInt(x -> Math.abs(positions.get(x.p()).ordinal() - 1 - rows.get(x.r()).order()))
                .thenComparing(x -> !sameQuantity(positions.get(x.p()), rows.get(x.r()))));
        boolean[] usedP = new boolean[positions.size()], usedR = new boolean[rows.size()];
        List<Match> out = new ArrayList<>();
        for (Pair x : pairs) {
            if (usedP[x.p()] || usedR[x.r()]) continue;
            usedP[x.p()] = true;
            usedR[x.r()] = true;
            double secondP = 0, secondR = 0;
            for (int j = 0; j < rows.size(); j++) if (j != x.r()) secondP = Math.max(secondP, s[x.p()][j]);
            for (int i = 0; i < positions.size(); i++) if (i != x.p()) secondR = Math.max(secondR, s[i][x.r()]);
            boolean auto = x.score() >= AUTO_MIN && x.score() - secondP >= AUTO_MARGIN && x.score() - secondR >= AUTO_MARGIN;
            out.add(new Match(positions.get(x.p()).itemId(), rows.get(x.r()).fileRow(), round(x.score()), auto ? Kind.AUTO : Kind.SUGGEST));
        }
        return out;
    }

    /** Оценка пары вне подбора — строка выбрана вручную: экран пишет «названия похожи слабо». */
    public static double score(String positionText, String rowText, List<Row> rows) {
        return round(pairScore(Weights.of(rows), positionText, rowText));
    }

    private static double pairScore(Weights w, String positionText, String rowText) {
        double v = w.score(positionText, rowText);
        return conflicting(positionText, rowText) ? v * CONFLICT_FACTOR : v;
    }

    /** Числа текста — номера, размеры, цифры кодов («№ 5», «60 мм», «MSL24» → 24); ведущие нули не в счёт. */
    static Set<String> numbers(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        Matcher m = DIGITS.matcher(text);
        while (m.find()) out.add(m.group().replaceFirst("^0+(?=\\d)", ""));
        return out;
    }

    /** У позиции есть число, которого нет в строке, а у строки — которого нет у позиции: соседняя модель или размер. */
    static boolean conflicting(String positionText, String rowText) {
        Set<String> p = numbers(positionText), r = numbers(rowText);
        return !r.containsAll(p) && !p.containsAll(r);
    }

    private static boolean sameQuantity(Position p, Row r) {
        return p.quantity() != null && r.quantity() != null && r.quantity().compareTo(BigDecimal.valueOf(p.quantity())) == 0;
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    record Token(String stem, boolean code) {}

    static List<Token> tokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        String low = fold(text.toLowerCase(Locale.ROOT).replace('ё', 'е'));
        Set<String> seen = new LinkedHashSet<>();
        List<Token> out = new ArrayList<>();
        for (String chunk : low.split("\\s+")) {
            String joined = chunk.replaceAll("[^\\p{L}\\p{N}]", "");
            if (joined.length() >= 3 && joined.chars().anyMatch(Character::isDigit) && seen.add(joined)) out.add(new Token(joined, true));
            for (String word : chunk.split("[^\\p{L}\\p{N}]+")) {
                boolean digit = word.chars().anyMatch(Character::isDigit);
                if (word.length() < (digit ? 2 : 3)) continue;
                String stem = digit ? word : (word.length() > 5 ? word.substring(0, 5) : word);
                if (seen.add(stem)) out.add(new Token(stem, digit));
            }
        }
        return out;
    }

    /** Латинские двойники кириллицы — в кириллицу (одинаково в обе стороны сравнения). */
    static String fold(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            b.append(switch (c) {
                case 'a' -> 'а'; case 'b' -> 'в'; case 'c' -> 'с'; case 'e' -> 'е'; case 'h' -> 'н'; case 'k' -> 'к';
                case 'm' -> 'м'; case 'o' -> 'о'; case 'p' -> 'р'; case 't' -> 'т'; case 'x' -> 'х'; case 'y' -> 'у';
                default -> c;
            });
        }
        return b.toString();
    }

    static final class Weights {
        private final Map<String, Integer> df = new HashMap<>();
        private final int n;

        private Weights(int n) {
            this.n = n;
        }

        static Weights of(List<Row> rows) {
            Weights w = new Weights(rows.size());
            for (Row r : rows) {
                Set<String> stems = new HashSet<>();
                for (Token t : tokens(r.text())) stems.add(t.stem());
                for (String st : stems) w.df.merge(st, 1, Integer::sum);
            }
            return w;
        }

        double weight(Token t) {
            int d = df.getOrDefault(t.stem(), 0);
            double base = Math.log(1.0 + n / (d == 0 ? 0.5 : d));
            return t.code() ? base * CODE_WEIGHT : base;
        }

        double score(String positionText, String rowText) {
            List<Token> tp = tokens(positionText), tr = tokens(rowText);
            if (tp.isEmpty() || tr.isEmpty()) return 0;
            Set<String> sp = new HashSet<>(), sr = new HashSet<>();
            tp.forEach(t -> sp.add(t.stem()));
            tr.forEach(t -> sr.add(t.stem()));
            double num1 = 0, den1 = 0, num2 = 0, den2 = 0;
            for (Token t : tp) {
                double wt = weight(t);
                den1 += wt;
                if (sr.contains(t.stem())) num1 += wt;
            }
            for (Token t : tr) {
                double wt = weight(t);
                den2 += wt;
                if (sp.contains(t.stem())) num2 += wt;
            }
            return 0.75 * (num1 / den1) + 0.25 * (num2 / den2);
        }
    }
}
