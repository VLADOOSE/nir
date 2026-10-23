package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.service.supplierreply.MoneyText;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Прайс строками текста (Word без таблицы, PDF без сетки; спека §5.2 п. 2): «1. Стерилизатор … СК – 16 л ……… 957 000
 * руб.» — наименование, заполнитель (точки, многоточие, три пробела или табуляция), одна цена в конце строки, валюта.
 * Раздел — строка без цены в верхнем регистре или с «:» в конце. Остальное — текст документа (шапка и условия).
 */
public final class PriceTextLines {

    public record Result(List<ParsedPriceRow> rows, List<String> text, String currency) {}

    static final int ROWS_MAX = 2000;
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d{1,4}[.)]\\s+");
    private static final Pattern LINE = Pattern.compile("(?iu)^(?<name>.*?\\p{L}.*?)\\s*(?:[.…·_]{2,}|\\s{3,}|\\t)\\s*"
            + "(?<price>\\d{1,3}(?:[\\s\\u00A0]\\d{3})+(?:[.,]\\d{1,2})?|\\d{4,}(?:[.,]\\d{1,2})?)\\s*"
            + "(?<cur>руб\\.?|рублей|рубля|₽|тенге|тг\\.?|₸|\\$|usd|€|eur|евро|юан\\p{L}*|cny)?\\.?\\s*$");
    private static final Pattern TAIL = Pattern.compile("[\\s.…·_]+$");

    private PriceTextLines() {}

    public static Result read(List<String> lines) {
        List<ParsedPriceRow> rows = new ArrayList<>();
        List<String> text = new ArrayList<>();
        Set<String> currencies = new TreeSet<>();
        String section = null;
        int n = 0;
        for (String raw : lines == null ? List.<String>of() : lines) {
            n++;
            if (raw == null) continue;
            String line = raw.replace('\u00A0', ' ').strip();
            if (line.isEmpty()) continue;
            Matcher m = LINE.matcher(NUMBERED.matcher(line).replaceFirst(""));
            if (m.find()) {
                BigDecimal price = MoneyText.amount(m.group("price"));
                String name = TAIL.matcher(m.group("name")).replaceAll("").replaceAll("\\s+", " ").strip();
                if (price != null && price.signum() > 0 && name.length() >= 3) {
                    rows.add(new ParsedPriceRow(n, section, cut(name, 1000), null, List.of(price), null));
                    String cur = m.group("cur") == null ? null : MoneyText.currency(m.group("cur"));
                    if (cur != null) currencies.add(cur);
                    if (rows.size() >= ROWS_MAX) break;
                    continue;
                }
            }
            if (section(line)) section = cut(line.replaceAll("[:：]\\s*$", "").strip(), 255);
            text.add(line);
        }
        String currency = currencies.isEmpty() ? null : currencies.size() == 1 ? currencies.iterator().next() : MoneyText.MIXED;
        return new Result(rows, text, currency);
    }

    /** Раздел: не длиннее 120, без чисел от трёх цифр, в верхнем регистре (≥ 80 % букв) или с «:» в конце. */
    static boolean section(String line) {
        if (line.length() > 120 || line.matches(".*\\d{3,}.*")) return false;
        if (line.endsWith(":")) return true;
        int letters = 0, upper = 0;
        for (char c : line.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) upper++;
            }
        }
        return letters >= 4 && upper * 5 >= letters * 4;
    }

    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
