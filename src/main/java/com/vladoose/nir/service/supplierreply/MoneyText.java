package com.vladoose.nir.service.supplierreply;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
     * Валюта не ясна: в таблице её нет, а в тексте документа вне таблицы — разные валюты или суммы в тысячах / миллионах
     * («тыс. руб.», «т.р.»: цены могут быть в тысячах — рублями их считать нельзя, цена вышла бы в 1000 раз меньше). Не
     * валюта рынка — цены сами не подставляются; курс не применить — неизвестно, что пересчитывать.
     */
    public static final String MIXED = "MIXED";

    /** Валюты, которые можно выбрать файлу на экране сверки: тенге, рубли, доллары, евро, юани («у.е.» и MIXED — нельзя). */
    public static final List<String> CHOOSABLE = List.of("KZT", "RUB", "USD", "EUR", "CNY");

    // ---- Валюта в свободном тексте (абзацы Word, строки над шапкой и под «Итого») -------------------------------
    // Строже, чем в ячейках таблицы ({@link #currency}): в тексте валюта — только вплотную к числу («18 450,00 руб.»,
    // «$1 200») или в явной фразе («цены в рублях», «Валюта: тенге»). Адреса почты и сайтов вырезаются до поиска;
    // реквизиты (ИИК, IBAN, р/с, к/с, БИК, SWIFT, номера счетов) и курс («по курсу …», «1 USD = 480 тенге») — до конца
    // своей фразы. Так «Тел./ТГ: +7…», «ХС, ТГ, ЛПВП», «модель ТГ-12», «вилка евро», «Евро-3», «www.rub-med.ru»,
    // «Рублёвское ш.» и счета «(KZT)», «(USD)» валютой не становятся.

    /** Слово валюты кончилось: дальше не буква, не цифра и не дефис («Евро-3», «ТГ-12» — не валюта). */
    private static final String END = "(?![\\p{L}\\p{N}-])";
    /** Код ISO кончился: дальше не буква и не цифра. */
    private static final String CODE_END = "(?![\\p{L}\\p{N}])";
    /** Формы валют без границы слева (её ставит шаблон вокруг: число перед словом, «в » перед ним). */
    private static final String KZT_W = "₸|kzt" + CODE_END + "|тенге" + END + "|теңге" + END + "|тнг" + END + "|тг" + END;
    private static final String RUB_W = "₽|rub(?:les?)?" + CODE_END + "|rur" + CODE_END
            + "|руб(?:ль|ля|ли|лей|лях|лям|лями|лю|лем)" + END + "|руб\\.|руб" + END;
    private static final String USD_W = "\\$|usd" + CODE_END + "|доллар(?:а|ов|ах|ам|ами|е|у|ы)?" + END
            + "|долл\\.|долл" + END + "|дол\\.";
    private static final String EUR_W = "€|eur" + CODE_END + "|euro" + END + "|евро" + END;
    private static final String CNY_W = "¥|cny" + CODE_END + "|rmb" + CODE_END + "|юан(?:ь|я|и|ей|ях|ям|ями|ю|ем)" + END;
    private static final String UE_W = "у\\.\\s?е\\.";
    private static final String ANY = KZT_W + "|" + RUB_W + "|" + USD_W + "|" + EUR_W + "|" + CNY_W + "|" + UE_W;
    /**
     * Число с начала: «18 450,00», «1 250 000 ,00» — не из середины другого числа и не из кода («R12»). Притяжательные
     * кванторы: длинная строка цифр проходится один раз, без перебора.
     */
    private static final String NUM = "(?<![\\p{L}\\p{N}])(?<!\\d[\\s.,'])(?<!\\d\\s[.,'])\\d++(?:(?:\\s?[.,']\\s?|\\s)\\d++)*+";
    /** Число, затем валюта («18 450,00 руб.», «1 200 $», «500 р.»); между ними — пропись в скобках («1 250 000 (…) тенге»). */
    private static final Pattern AFTER_NUMBER = Pattern.compile(
            NUM + "\\s*+(?:\\([^()\\n]{0,200}\\)\\s*+)?(" + ANY + "|р\\.(?!\\p{L}))");
    /** Знак или код валюты, затем число («$1 200», «USD 1 200», «руб. 18 450»); словом перед числом валюту не пишут. */
    private static final Pattern BEFORE_NUMBER = Pattern.compile(
            "([₸₽$€¥]|(?<![\\p{L}\\p{N}])(?:kzt|rub|rur|usd|eur|cny|rmb)|(?<![\\p{L}\\p{N}])руб\\.)\\s*+\\d");
    /** «в рублях», «в тенге», «в долларах (США)», «в долл. США», «в евро», «в юанях», «в у.е.», «в USD» («в ТГ» — Telegram). */
    private static final Pattern IN_CURRENCY = Pattern.compile("(?<![\\p{L}\\p{N}])в\\s++(рубл(?:ях|и|ь)" + END
            + "|руб\\.|руб" + END + "|тенге" + END + "|теңге" + END + "|доллар(?:ах|ы)" + END + "|долл\\.|долл" + END
            + "|дол\\.|евро" + END + "|euro" + END + "|юан(?:ях|и)" + END + "|" + UE_W
            + "|(?:kzt|rub|rur|usd|eur|cny|rmb)" + CODE_END + "|[₸₽$€¥])");
    /** «Валюта: тенге», «Валюта предложения — рубли», «валюта платежа KZT». */
    private static final Pattern NAMED_CURRENCY = Pattern.compile(
            "(?<![\\p{L}\\p{N}])валют\\p{L}*+[^.;!?\\n]{0,40}?(?<![\\p{L}\\p{N}])(" + ANY + ")");
    /** Суммы в тысячах / миллионах: «18,4 тыс. руб.», «1,2 млн тенге», «50 т.р.» — валюта не ясна (MIXED). */
    private static final Pattern SCALED = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:(?:тыс|млн|млрд)\\.?\\s?(?:" + ANY + "|р\\.)|т\\.\\s?р\\.)");
    /** Реквизиты — до конца своей фразы: ИИК, IBAN, р/с, расчётный и корр. счёт, БИК, SWIFT, КБе, номер IBAN, 20 цифр счёта. */
    private static final Pattern REQUISITES = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:иик|iban|swift|бик|bic|кбе|р/сч?|р\\.\\s?сч?\\.|расч(?:етный|\\.)\\s*сч(?:ет|\\.)?"
                    + "|к/сч?|корр?(?:\\.|/)?\\s?сч(?:ет|\\.)?|корреспондентский\\s+сч(?:ет)?)(?![\\p{L}\\p{N}])"
                    + "|(?<![\\p{L}\\p{N}])[a-z]{2}\\d{2}(?:\\s?[0-9a-z]{4}){3,}+|(?<!\\d)\\d{20}(?!\\d)");
    /** Курс — до конца своей фразы: «по курсу ЦБ…», «курс доллара 480 тг», «при курсах валют не выше …». */
    private static final Pattern RATE = Pattern.compile("(?<![\\p{L}\\p{N}])курс(?:а|у|е|ом|ы|ов|ам|ами|ах)?(?![\\p{L}\\p{N}])");
    /** Равенство курса: «1 USD = 480 тенге», «USD/KZT = 480», «1 у.е. = 1 евро». */
    private static final Pattern EQUATION = Pattern.compile(
            "(?:" + ANY + ")(?:\\s*+/\\s*+(?:" + ANY + "))?\\s*+=\\s*+\\d[\\d.,' ]*+(?:\\s*+(?:" + ANY + "))?");
    /** Домены, которые вырезаются как адрес сайта («rub-med.ru»). */
    private static final Set<String> TLD = Set.of("ru", "kz", "com", "net", "org", "рф", "su", "by", "uz", "kg", "ua",
            "info", "biz", "pro", "online", "site", "shop", "store", "med", "group", "io", "eu", "de", "cn", "uk", "us");

    private MoneyText() {}

    /**
     * Валюта, найденная в строке текста: code — код (MIXED — сумма в тысячах / миллионах), start / end — где в строке
     * (для фрагмента «по тексту «…»»).
     */
    public record TextHit(String code, int start, int end) {}

    /** Валюты, названные в строке текста (коды; MIXED — сумма в тысячах); не названо — пусто. */
    public static Set<String> textCurrencies(String s) {
        Set<String> out = new TreeSet<>();
        for (TextHit h : textHits(s)) out.add(h.code());
        return out;
    }

    /** Находки валюты в строке текста — по порядку в строке. */
    public static List<TextHit> textHits(String s) {
        if (s == null || s.isBlank()) return List.of();
        char[] c = spaces(s).toLowerCase(Locale.ROOT).replace('ё', 'е').toCharArray();
        blankLinks(c);
        blankClauses(c, REQUISITES);
        blankEquations(c);
        blankClauses(c, RATE);
        List<TextHit> out = new ArrayList<>();
        Matcher scaled = SCALED.matcher(new String(c));
        while (scaled.find()) {
            out.add(new TextHit(MIXED, scaled.start(), scaled.end()));
            Arrays.fill(c, scaled.start(), scaled.end(), ' ');   // «тыс. руб.» — не рубли
        }
        String t = new String(c);
        for (Pattern p : List.of(AFTER_NUMBER, BEFORE_NUMBER, IN_CURRENCY, NAMED_CURRENCY)) {
            for (Matcher m = p.matcher(t); m.find(); ) out.add(new TextHit(code(m.group(1)), m.start(), m.end()));
        }
        out.sort(Comparator.comparingInt(TextHit::start));
        return out;
    }

    /**
     * Фрагмент строки вокруг находки для людей («…на сумму 18 450,00 руб., НДС…»): не длиннее max, по границам слов,
     * «…» на обрезанных концах.
     */
    public static String fragment(String line, TextHit h, int max) {
        String s = spaces(line);
        if (s.length() <= max) return s.strip();
        int a = Math.min(h.start(), s.length()), b = Math.min(h.end(), s.length());
        int from, to;
        if (b - a >= max) {   // находка длиннее окна (пропись в скобках) — её конец, где валюта
            from = b - max;
            to = b;
        } else {
            from = Math.max(0, Math.min(a - (max - (b - a)) / 2, s.length() - max));
            to = Math.min(s.length(), from + max);
        }
        if (from > 0) {
            int sp = s.indexOf(' ', from);
            if (sp >= 0 && sp < a) from = sp + 1;
        }
        if (to < s.length()) {
            int sp = s.lastIndexOf(' ', to);
            if (sp >= b) to = sp;
        }
        return (from > 0 ? "…" : "") + s.substring(from, to).strip() + (to < s.length() ? "…" : "");
    }

    /** Код по найденной форме валюты. */
    private static String code(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        if (t.startsWith("₸") || t.startsWith("kzt") || t.startsWith("тенге") || t.startsWith("теңге") || t.startsWith("т")) return "KZT";
        if (t.startsWith("₽") || t.startsWith("ru") || t.startsWith("р")) return "RUB";
        if (t.startsWith("$") || t.startsWith("usd") || t.startsWith("дол")) return "USD";
        if (t.startsWith("€") || t.startsWith("eur") || t.startsWith("евро")) return "EUR";
        if (t.startsWith("¥") || t.startsWith("cny") || t.startsWith("rmb") || t.startsWith("юан")) return "CNY";
        return "UE";
    }

    /** Адреса почты и сайтов — пробелами (длина строки та же: места находок совпадают с исходной строкой). */
    private static void blankLinks(char[] c) {
        int i = 0;
        while (i < c.length) {
            if (Character.isWhitespace(c[i])) {
                i++;
                continue;
            }
            int j = i;
            while (j < c.length && !Character.isWhitespace(c[j])) j++;
            if (link(new String(c, i, j - i))) Arrays.fill(c, i, j, ' ');
            i = j;
        }
    }

    private static boolean link(String token) {
        if (token.indexOf('@') >= 0 || token.startsWith("http://") || token.startsWith("https://") || token.contains("www.")) return true;
        int from = 0, to = token.length();
        while (from < to && !Character.isLetterOrDigit(token.charAt(from))) from++;
        while (to > from && !Character.isLetterOrDigit(token.charAt(to - 1))) to--;
        String t = token.substring(from, to);
        int dot = t.lastIndexOf('.');
        if (dot <= 0 || !TLD.contains(t.substring(dot + 1))) return false;
        for (int k = 0; k < t.length(); k++) {
            char ch = t.charAt(k);
            if (!Character.isLetterOrDigit(ch) && ch != '.' && ch != '-' && ch != '/') return false;
        }
        return true;
    }

    /** От начала совпадения до конца фразы — пробелами: «;», перевод строки, «.» или «,» перед пробелом / концом. */
    private static void blankClauses(char[] c, Pattern start) {
        String t = new String(c);
        Matcher m = start.matcher(t);
        int from = 0;
        while (from < t.length() && m.find(from)) {
            int e = clauseEnd(t, m.end());
            Arrays.fill(c, m.start(), e, ' ');
            from = Math.max(e, m.start() + 1);
        }
    }

    private static int clauseEnd(String t, int i) {
        for (; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == ';' || ch == '\n') return i;
            if ((ch == '.' || ch == ',') && (i + 1 == t.length() || Character.isWhitespace(t.charAt(i + 1)))) return i;
        }
        return t.length();
    }

    /** Равенства курса («1 USD = 480 тенге») — пробелами вместе с числом перед ними. */
    private static void blankEquations(char[] c) {
        Matcher m = EQUATION.matcher(new String(c));
        while (m.find()) {
            int s = m.start();
            while (s > 0 && (Character.isDigit(c[s - 1]) || c[s - 1] == ' ')) s--;
            Arrays.fill(c, s, m.end(), ' ');
        }
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
