package com.vladoose.nir.service.supplierreply;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Числа и валюта из ячеек КП поставщика (спека 2026-10-15 §3.2): «1 360 000 тенге», «7 200,00», «1 уп». Разделители
 * разрядов — пробелы, неразрывные пробелы, апостроф; запятая — дробная, если она одна (так пишут в KZ и RU), а
 * несколько запятых или точек подряд группами по три — разряды («1,360,000», «1.360.000»).
 */
public final class MoneyText {

    /** Больше — не цена, а мусор (предел NUMERIC(15,2), как у КП клиенту). */
    static final BigDecimal MAX = new BigDecimal("9999999999999.99");
    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d '.,]*");
    private static final Pattern THOUSANDS_DOT = Pattern.compile("\\d{1,3}(\\.\\d{3}){2,}");
    private static final Pattern THOUSANDS_COMMA = Pattern.compile("\\d{1,3}(,\\d{3}){2,}");
    private static final Pattern QTY = Pattern.compile("^\\s*(\\d+(?:[.,]\\d+)?)\\s*(\\p{L}[\\p{L}.]*)?");
    private static final Pattern TG = Pattern.compile("(^|[^\\p{L}])тг([^\\p{L}]|$)");
    private static final Pattern RUB = Pattern.compile("(^|[^\\p{L}])руб");
    /** «у.е.», «у. е.» — условные единицы: валюта не названа, но это точно не валюта рынка — цены не подставляются. */
    private static final Pattern UE = Pattern.compile("(^|[^\\p{L}])у\\.\\s?е\\.");

    /**
     * Валюта не ясна: в таблице её нет, а в тексте документа вне таблицы названы разные валюты (2026-10-16). Не валюта
     * рынка — цены сами не подставляются; курс не применить — неизвестно, из какой валюты пересчитывать.
     */
    public static final String MIXED = "MIXED";

    /**
     * Валюта в свободном тексте (абзацы Word, ячейки вне таблицы, строка «Итого»): только явное слово или знак. Строже
     * {@link #currency}: в описаниях изделий и реквизитах — «противорубцовый», «Рубцовск», «евростандарт», «Neurosoft»,
     * «rubber».
     */
    private static final List<Map.Entry<String, Pattern>> TEXT = List.of(
            Map.entry("KZT", Pattern.compile("₸|(?<![\\p{L}\\p{N}])kzt(?![\\p{L}\\p{N}])|(?<!\\p{L})тенге|(?<!\\p{L})тг(?!\\p{L})")),
            Map.entry("RUB", Pattern.compile("₽|(?<![\\p{L}\\p{N}])rub(?![\\p{L}\\p{N}])|(?<!\\p{L})руб(?:\\.|(?!\\p{L})|л\\p{L}*)")),
            Map.entry("USD", Pattern.compile("\\$|(?<![\\p{L}\\p{N}])usd(?![\\p{L}\\p{N}])|(?<!\\p{L})долл(?:\\.|(?!\\p{L})|ар\\p{L}*)")),
            Map.entry("EUR", Pattern.compile("€|(?<![\\p{L}\\p{N}])eur(?![\\p{L}\\p{N}])|(?<!\\p{L})евро(?!\\p{L})")),
            Map.entry("CNY", Pattern.compile("¥|(?<![\\p{L}\\p{N}])cny(?![\\p{L}\\p{N}])|(?<!\\p{L})юан\\p{L}*")),
            Map.entry("UE", Pattern.compile("(?<!\\p{L})у\\.\\s?е\\.")));

    private MoneyText() {}

    /** Валюты, названные в тексте (коды); не названо — пусто. */
    public static Set<String> textCurrencies(String s) {
        if (s == null || s.isBlank()) return Set.of();
        String t = spaces(s).toLowerCase(Locale.ROOT);
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, Pattern> e : TEXT) if (e.getValue().matcher(t).find()) out.add(e.getKey());
        return out;
    }

    /** Сумма или цена; без цифр, отрицательная или больше MAX — null. */
    public static BigDecimal amount(String s) {
        if (s == null) return null;
        String t = spaces(s).trim();
        Matcher m = NUMBER.matcher(t);
        if (!m.find()) return null;
        String n = m.group().replace(" ", "").replace("'", "").replaceAll("[.,]+$", "");
        if (n.isEmpty() || n.equals("-")) return null;
        String digits = n.startsWith("-") ? n.substring(1) : n;
        int comma = n.lastIndexOf(','), dot = n.lastIndexOf('.');
        String plain;
        if (comma >= 0 && dot >= 0) {
            plain = comma > dot ? n.replace(".", "").replace(',', '.') : n.replace(",", "");
        } else if (comma >= 0) {
            plain = THOUSANDS_COMMA.matcher(digits).matches() ? n.replace(",", "") : n.replace(',', '.');
        } else if (dot >= 0) {
            plain = THOUSANDS_DOT.matcher(digits).matches() ? n.replace(".", "") : n;
        } else {
            plain = n;
        }
        try {
            BigDecimal v = new BigDecimal(plain).setScale(2, RoundingMode.HALF_UP);
            return v.signum() < 0 || v.compareTo(MAX) > 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Количество и единица: «1 уп» → 1 и «уп»; «10» → 10 без единицы; без числа — null. */
    public record Qty(BigDecimal value, String unit) {}

    public static Qty quantity(String s) {
        if (s == null) return null;
        Matcher m = QTY.matcher(spaces(s));
        if (!m.find()) return null;
        BigDecimal v = new BigDecimal(m.group(1).replace(',', '.')).stripTrailingZeros();
        if (v.scale() < 0) v = v.setScale(0);
        String unit = m.group(2) == null ? null : m.group(2).replaceAll("\\.+$", "").toLowerCase(Locale.ROOT);
        return new Qty(v, unit == null || unit.isBlank() ? null : unit);
    }

    /** Код валюты по тексту ячейки или заголовка; не названа — null. */
    public static String currency(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.toLowerCase(Locale.ROOT);
        if (t.contains("₸") || t.contains("kzt") || t.contains("тенге") || TG.matcher(t).find()) return "KZT";
        if (t.contains("₽") || t.contains("rub") || RUB.matcher(t).find()) return "RUB";
        if (t.contains("$") || t.contains("usd") || t.contains("долл")) return "USD";
        if (t.contains("€") || t.contains("eur") || t.contains("евро")) return "EUR";
        if (t.contains("¥") || t.contains("cny") || t.contains("юан")) return "CNY";
        if (UE.matcher(t).find()) return "UE";
        return null;
    }

    /** Подпись валюты для людей. */
    public static String label(String code) {
        if (code == null) return "";
        return switch (code) {
            case "KZT" -> "тг";
            case "RUB" -> "руб.";
            case "USD" -> "$";
            case "EUR" -> "€";
            case "CNY" -> "юанях";
            case "UE" -> "у.е.";
            case MIXED -> "(валюта?)";
            default -> code;
        };
    }

    /** Неразрывный, узкий неразрывный и тонкий пробелы — обычным (так их ставит Excel и Word в числах). */
    private static String spaces(String s) {
        return s.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ');
    }
}
