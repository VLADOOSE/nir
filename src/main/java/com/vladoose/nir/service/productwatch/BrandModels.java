package com.vladoose.nir.service.productwatch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Бренды и коды моделей (спека §5.3): разделители («-», пробел, точка, кавычки) и переходы буква↔цифра не значимы —
 * «ЭХВЧ 350» = «ЭХВЧ-350-03»; у кода с цифрами и у короткого слова (≤ 3 букв) — граница слова в конце («СК-16» не
 * совпадает с «СК-160», «обн» — с «обновлением»), длинное слово — стем («аксион» ловит «Аксиона»).
 */
public final class BrandModels {

    public record Code(String label, Pattern pattern, Long productId) {}

    public record Hit(String label, Long productId) {}

    private static final String SEP = "[\\s\\-./«»\"“”'–—]*";
    private static final Pattern PART = Pattern.compile("\\p{L}+|\\p{N}+");
    /** Бренды без двусмысленности — общий список у любого лота («ЭМА», «Орион», «Монитор» — только в своей группе). */
    public static final List<String> GLOBAL_BRANDS = List.of("фотек", "fotek", "аксион", "axion", "эмалед", "нпп монитор");

    private BrandModels() {}

    public static Pattern compile(String word) {
        String n = ProductText.normalize(word).strip();
        List<String> parts = new ArrayList<>();
        Matcher m = PART.matcher(n);
        while (m.find()) parts.add(m.group());
        StringBuilder q = new StringBuilder("(?<![\\p{L}\\p{N}])");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) q.append(SEP);
            q.append(Pattern.quote(parts.get(i)));
        }
        if (ProductText.digits(n) > 0 || ProductText.letters(n) <= 3) q.append("(?![\\p{L}\\p{N}])");
        return Pattern.compile(q.toString(), Pattern.UNICODE_CHARACTER_CLASS);
    }

    /** Коды моделей из названия товара: слово с ≥ 2 буквами и ≥ 1 цифрой («ДКИ-Н-11», «ЭХВЧ-350-03»). */
    public static List<String> modelCodes(String productName) {
        List<String> out = new ArrayList<>();
        if (productName == null) return out;
        for (String raw : productName.split("[\\s«»\"“”()\\[\\],;]+")) {
            String t = raw.replaceAll("^[\\-–—./]+|[\\-–—./]+$", "");
            if (!t.isEmpty() && ProductText.letters(t) >= 2 && ProductText.digits(t) >= 1) out.add(t);
        }
        return out;
    }

    /** Код — в общий список (у любого лота): ≥ 2 цифр и не короче 5 знаков без разделителей. */
    public static boolean globalCode(String code) {
        String squashed = ProductText.normalize(code).replaceAll("[^\\p{L}\\p{N}]", "");
        return ProductText.digits(squashed) >= 2 && squashed.length() >= 5;
    }

    /** Первое совпадение среди кодов: метка — как в тексте. */
    public static Hit first(List<Code> codes, String normalizedText) {
        if (normalizedText == null || normalizedText.isEmpty()) return null;
        for (Code c : codes) {
            Matcher m = c.pattern().matcher(normalizedText);
            if (m.find()) return new Hit(m.group(), c.productId());
        }
        return null;
    }
}
