package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.service.productwatch.BrandModels;
import com.vladoose.nir.service.productwatch.ProductText;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Коды моделей строк прайса и ключ товара (решение плана 8): коды — как у наших товаров ({@link BrandModels#modelCodes}),
 * плюс «СК – 16» с пробелами вокруг тире; ключ — нижний регистр, латинские двойники → кириллица, без разделителей
 * («ДКИ-Н-12» → «дкин12»). Одинаковое правило с обеих сторон — товар сайта и строка прайса сходятся по ключу.
 */
public final class PriceListCodes {

    private static final Pattern SPACED = Pattern.compile("(?<![\\p{L}\\p{N}])(\\p{L}{2,6})\\s+[–—-]\\s+(\\d{2,4})(?!\\p{N})");
    private static final String LATIN = "abcehkmoptxy";
    private static final String CYRILLIC = "авсенкмортху";

    private PriceListCodes() {}

    public static List<String> codes(String name) {
        List<String> out = new ArrayList<>(BrandModels.modelCodes(name));
        if (name != null) {
            Matcher m = SPACED.matcher(name);
            while (m.find()) {
                String c = m.group(1) + "-" + m.group(2);
                if (ProductText.digits(c) >= 2 && !out.contains(c)) out.add(c);
            }
        }
        return out;
    }

    public static String key(String code) {
        if (code == null) return null;
        StringBuilder b = new StringBuilder(code.length());
        for (char ch : code.toLowerCase(Locale.ROOT).replace('ё', 'е').toCharArray()) {
            if (!Character.isLetterOrDigit(ch)) continue;
            int i = LATIN.indexOf(ch);
            b.append(i >= 0 ? CYRILLIC.charAt(i) : ch);
        }
        return b.isEmpty() ? null : b.toString();
    }

    public static Set<String> keys(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : codes(text)) {
            String k = key(c);
            if (k != null) out.add(k);
        }
        return out;
    }
}
