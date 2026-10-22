package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.TextPrice;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Цены в тексте КП-буклета без таблицы (§16 «Примеры КП оператора», находка 4): у АМИКО «ПроСкан-2000» в одной ячейке —
 * «Стоимость оборудования со скидкой – 5 000 000 рублей. … (без АРМ врача) – 4 500 000 рублей. … Ввод в эксплуатацию –
 * 200 000 рублей», у Шармстар — «Charmstar Super Slim – 110 000 рублей». Текст делится на фразы («. » перед заглавной,
 * перевод строки); в каждой — первая цена (у «Стоимость 1 935 000 тенге х4 шт.-7 740 000 тенге» — за штуку, не сумма
 * строки): число (тысячи через пробел или от 4 цифр), за которым сразу валюта, или перед которым в пределах 40 знаков
 * «стоимость / цена / сумма / составляет / прайс». Не цены: фразы «Итого / Всего …», реквизиты (IBAN, ИИК, БИК, р/с),
 * телефоны, даты, годы, количество («2 000 шт.»), проценты. Одинаковые сумма и валюта — один раз; до {@link #MAX}.
 * Подсказка на экране сверки — не подстановка (у АМИКО цены три, какая из них ответ — решает человек).
 */
public final class DocumentPrices {

    static final int MAX = 10;
    static final int TEXT = 160;

    private static final Pattern SPLIT = Pattern.compile("(?<=[.;!?])\\s+(?=[\\p{Lu}«\"])|\\R+");
    private static final Pattern MONEY = Pattern.compile(
            "(?<![\\d\\p{L}.,])(?:\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})+|\\d{4,})(?:[.,]\\s?\\d{1,2})?(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?:\\+7|8)[ \\-]?\\(?\\d{3}\\)?[ \\-]?\\d{3}[ \\-]?\\d{2}[ \\-]?\\d{2}");
    private static final Pattern KEYWORD = Pattern.compile("(?iu)(стоимост|цена|цены|сумм|составля|прайс|price|cost)");
    private static final Pattern TOTAL = Pattern.compile("(?iu)^\\W*(итого|всего|итог)");
    private static final Pattern REQUISITES = Pattern.compile(
            "(?iu)(\\b[A-Z]{2}\\d{2}[ ]?[0-9A-Z]{4}\\b|\\b(иик|iban|бик|bic|swift|кбе|бин|инн|кпп|огрн)\\b|р/с|расч[её]тн|корр)");
    private static final Pattern QTY = Pattern.compile("(?iu)^\\s*(шт|ед\\b|ед\\.|упак|уп\\b|компл|набор|пар\\b|флак)");
    private static final Pattern YEAR = Pattern.compile("(?iu)^\\s*(г\\.|г\\b|год)");
    /** Валюта сразу за числом (код — как MoneyText). */
    private static final List<Map.Entry<Pattern, String>> CURRENCY = List.of(
            Map.entry(Pattern.compile("(?iu)^\\s*(руб|₽|rub|р\\.)"), "RUB"),
            Map.entry(Pattern.compile("(?iu)^\\s*(тенге|теңге|тг|тнг|₸|kzt)"), "KZT"),
            Map.entry(Pattern.compile("(?iu)^\\s*(\\$|usd|долл)"), "USD"),
            Map.entry(Pattern.compile("(?iu)^\\s*(€|eur|евро)"), "EUR"),
            Map.entry(Pattern.compile("(?iu)^\\s*(юан|cny|rmb|¥)"), "CNY"));

    private DocumentPrices() {}

    public static List<TextPrice> find(List<String> lines) {
        List<TextPrice> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : lines) {
            if (line == null) continue;
            for (String raw : SPLIT.split(line.replace(' ', ' '))) {
                String f = raw.strip();
                if (f.isEmpty() || TOTAL.matcher(f).find() || REQUISITES.matcher(f).find()) continue;
                TextPrice p = first(f);
                if (p == null || !seen.add(p.amount().stripTrailingZeros().toPlainString() + "|" + p.currency())) continue;
                out.add(p);
                if (out.size() == MAX) return out;
            }
        }
        return out;
    }

    /** Первая цена фразы: валюта сразу за числом или «стоимость / цена…» перед ним; иначе null. */
    private static TextPrice first(String f) {
        String scan = blankPhones(f);
        Matcher m = MONEY.matcher(scan);
        while (m.find()) {
            String after = scan.substring(m.end());
            if (QTY.matcher(after).find() || YEAR.matcher(after).find() || after.stripLeading().startsWith("%")) continue;
            String currency = currency(after);
            boolean keyword = KEYWORD.matcher(scan.substring(Math.max(0, m.start() - 40), m.start())).find();
            if (currency == null && !keyword) continue;
            BigDecimal v = amount(m.group());
            if (v == null) continue;
            return new TextPrice(v, currency, text(f));
        }
        return null;
    }

    private static String blankPhones(String f) {
        Matcher m = PHONE.matcher(f);
        StringBuilder b = new StringBuilder(f);
        while (m.find()) for (int i = m.start(); i < m.end(); i++) b.setCharAt(i, ' ');
        return b.toString();
    }

    private static String currency(String after) {
        for (Map.Entry<Pattern, String> c : CURRENCY) if (c.getKey().matcher(after).find()) return c.getValue();
        return null;
    }

    private static BigDecimal amount(String raw) {
        try {
            BigDecimal v = new BigDecimal(raw.replaceAll("[\\s\\u202f]", "").replace(',', '.'));
            return v.signum() <= 0 || v.precision() - v.scale() > 13 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(String f) {
        String t = f.replaceAll("[.;,]+$", "").strip();
        return t.length() <= TEXT ? t : t.substring(0, TEXT - 1).strip() + "…";
    }
}
