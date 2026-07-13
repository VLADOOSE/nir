package com.vladoose.nir.util;

import com.vladoose.nir.entity.Market;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Эвристический парс ответа поставщика на КП: извлекает цену (и по возможности срок поставки)
 * из вольного текста письма. Чистая функция без I/O — юнит-тестируется на корпусе.
 * Не бросает: при любой неоднозначности/сбое/отсутствии якоря → Optional.empty() (не гадает).
 */
public final class SupplierReplyPriceParser {

    private SupplierReplyPriceParser() {}

    public record ParsedPrice(BigDecimal price, String term, String matchedSnippet) {}

    /** Телефон (KZ/RU) — вырезаем до поиска цены, чтобы его цифры не стали кандидатом. */
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+7|8)[ \\-]?\\(?\\d{3}\\)?[ \\-]?\\d{3}[ \\-]?\\d{2}[ \\-]?\\d{2}");

    private static final Pattern DATE = Pattern.compile(
            "\\b\\d{1,2}[.\\-/]\\d{1,2}[.\\-/]\\d{2,4}\\b");

    /** Число-цена: сгруппированное тысячами ИЛИ ≥4 цифр подряд, с опц. десятичной частью. */
    private static final Pattern MONEY = Pattern.compile(
            "\\d{1,3}(?:[ \\u00A0.]\\d{3})+(?:,\\d{1,2})?|\\d{4,}(?:[.,]\\d{1,2})?");

    private static final Pattern PRICE_KEYWORD = Pattern.compile(
            "(?i)(цена|стоимост|сумма|за единиц|составля|прайс|price)");

    /** Суффикс количества сразу после числа — тогда это НЕ цена (шт/упаковок/ед/…). */
    private static final Pattern QTY_SUFFIX = Pattern.compile(
            "(?iu)^\\s{0,3}(шт|упаков|ед\\b|ед\\.|позиц|коробк|компл|набор|уп\\b)");

    private static final Pattern TERM = Pattern.compile(
            "(?i)срок[^.\\n\\r]{0,40}?(\\d{1,3})\\s*(рабочих\\s*)?(дн|недел|мес)\\w*");

    public static Optional<ParsedPrice> parse(String rawBody, Market market) {
        try {
            if (rawBody == null || rawBody.isBlank()) return Optional.empty();
            String text = EmailReplyText.stripToReply(rawBody);
            if (text.isBlank()) return Optional.empty();

            String term = extractTerm(text);
            // Скраб телефонов/дат — их цифры не должны стать кандидатами цены.
            String scrubbed = DATE.matcher(PHONE.matcher(text).replaceAll(" ")).replaceAll(" ");
            String[] currency = currencyAnchors(market);

            Matcher m = MONEY.matcher(scrubbed);
            BigDecimal bestVal = null;
            String bestRaw = null;
            int bestScore = 0;
            while (m.find()) {
                // число с суффиксом количества (шт/упак/…) — это не цена
                String tail = scrubbed.substring(m.end(), Math.min(scrubbed.length(), m.end() + 12));
                if (QTY_SUFFIX.matcher(tail).find()) continue;
                BigDecimal val = toBigDecimal(m.group());
                if (val == null) continue;
                int score = anchorScore(scrubbed, m.start(), m.end(), currency);
                // строго больше → при равном якоре побеждает ПЕРВОЕ (обычно цена за ед., не «итого»)
                if (score > bestScore) { bestScore = score; bestVal = val; bestRaw = m.group(); }
            }
            if (bestScore == 0 || bestVal == null) return Optional.empty(); // нет якоря → не гадаем
            return Optional.of(new ParsedPrice(bestVal, term, snippetAround(text, bestRaw)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String[] currencyAnchors(Market market) {
        return market == Market.RF
                ? new String[]{"₽", "руб", "rub"}
                : new String[]{"₸", "тенге", "тнг", "тг", "kzt"};
    }

    /** Якорь: валюта справа (≤6 симв.) или слово-цена слева (≤24 симв.) = сильный (2);
     *  валюта чуть дальше (≤12) = слабый (1); иначе 0. */
    private static int anchorScore(String text, int start, int end, String[] currency) {
        String after = text.substring(end, Math.min(text.length(), end + 6)).toLowerCase();
        for (String c : currency) if (after.contains(c)) return 2;
        String before = text.substring(Math.max(0, start - 24), start).toLowerCase();
        if (PRICE_KEYWORD.matcher(before).find()) return 2;
        String farAfter = text.substring(end, Math.min(text.length(), end + 12)).toLowerCase();
        for (String c : currency) if (farAfter.contains(c)) return 1;
        return 0;
    }

    /** "3 200 000" → 3200000; "3 200 000,00" → 3200000.00; "3.200.000" → 3200000. */
    private static BigDecimal toBigDecimal(String raw) {
        String s = raw.replace(" ", "").replace(" ", "");
        if (s.contains(",")) {
            s = s.replace(".", "").replace(",", ".");        // запятая — десятичная, точки — тысячи
        } else if (s.matches("\\d{1,3}(\\.\\d{3})+")) {
            s = s.replace(".", "");                           // точки — разделители тысяч
        } // иначе одиночная точка = десятичная — оставляем как есть
        try {
            BigDecimal bd = new BigDecimal(s);
            // NUMERIC(15,2): целая часть ≤ 13 цифр — иначе overflow при flush (откат poll-транзакции)
            if (bd.precision() - bd.scale() > 13) return null;
            return bd;
        } catch (NumberFormatException e) { return null; }
    }

    private static String extractTerm(String text) {
        Matcher m = TERM.matcher(text);
        return m.find() ? m.group().trim() : null;
    }

    private static String snippetAround(String text, String raw) {
        int i = text.indexOf(raw);
        if (i < 0) return raw;
        int from = Math.max(0, i - 20), to = Math.min(text.length(), i + raw.length() + 12);
        return text.substring(from, to).trim().replaceAll("\\s+", " ");
    }
}
