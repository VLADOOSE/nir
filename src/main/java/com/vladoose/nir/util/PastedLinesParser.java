package com.vladoose.nir.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «Вставить списком» (спека 2026-10-12 §4): текст из WhatsApp или письма → позиции. Строка — позиция; маркеры списка
 * в начале убираются. Количество распознаётся только в однозначных формах в конце строки — с единицей, со знаком
 * умножения после пробела, в скобках, после тире с пробелами. Поэтому модель «КФ-2», «SX2» или «Насадка №2» количеством
 * не считается. Бренд не угадывается.
 */
public final class PastedLinesParser {

    public record ParsedLine(String name, int quantity, String unit) {}

    public static final int MAX_LINES = 200;
    private static final int MAX_NAME = 255;
    private static final int MAX_QTY = 1_000_000;

    private static final String UNIT =
            "(шт|штук[аи]?|ед|компл|комплект(?:а|ов)?|набор(?:а|ов)?|упак|уп|фл|флакон(?:а|ов)?|пар[аы]?|кг|л|м)";
    private static final Pattern MARKER = Pattern.compile("^\\s*(?:\\d{1,3}\\s*[.)]|[-–—•*·])\\s+");
    /** «… 2 шт», «… — 2 шт.», «…: 3 компл», «…, 4 упак» */
    private static final Pattern QTY_UNIT =
            Pattern.compile("(?iu)(?:\\s*[-–—:,]\\s*|\\s+)(\\d{1,7})\\s*" + UNIT + "\\.?\\s*$");
    /** «… (2)», «… (2 шт)» */
    private static final Pattern QTY_PAREN = Pattern.compile("(?iu)\\s*\\((\\d{1,7})(?:\\s*" + UNIT + "\\.?)?\\)\\s*$");
    /** «… x2», «… × 3», «… х2» (кириллическая «х») — только после пробела: «SX2» — модель */
    private static final Pattern QTY_TIMES = Pattern.compile("(?iu)\\s+[xх×]\\s*(\\d{1,7})\\s*$");
    /** «… — 2», «… - 2» — тире с пробелами с обеих сторон («КФ-2» — модель) */
    private static final Pattern QTY_DASH = Pattern.compile("(?u)\\s+[-–—]\\s+(\\d{1,7})\\s*$");
    private static final Pattern TRAILING_SEP = Pattern.compile("[\\s\\-–—:,;.]+$");

    private PastedLinesParser() {}

    public static List<ParsedLine> parse(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<ParsedLine> out = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            if (out.size() >= MAX_LINES) break;
            ParsedLine line = parseLine(raw);
            if (line != null) out.add(line);
        }
        return out;
    }

    static ParsedLine parseLine(String raw) {
        String s = raw == null ? "" : raw.replace(' ', ' ').strip();
        s = MARKER.matcher(s).replaceFirst("");
        if (s.isBlank()) return null;
        int qty = 1;
        String unit = null;
        Matcher m;
        if ((m = QTY_UNIT.matcher(s)).find()) {
            qty = quantity(m.group(1));
            unit = normUnit(m.group(2));
            s = s.substring(0, m.start());
        } else if ((m = QTY_PAREN.matcher(s)).find()) {
            qty = quantity(m.group(1));
            unit = m.group(2) == null ? null : normUnit(m.group(2));
            s = s.substring(0, m.start());
        } else if ((m = QTY_TIMES.matcher(s)).find()) {
            qty = quantity(m.group(1));
            s = s.substring(0, m.start());
        } else if ((m = QTY_DASH.matcher(s)).find()) {
            qty = quantity(m.group(1));
            s = s.substring(0, m.start());
        }
        String name = TRAILING_SEP.matcher(s).replaceAll("").replaceAll("\\s+", " ").strip();
        if (name.isEmpty() || name.chars().noneMatch(Character::isLetter)) return null;
        if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
        return new ParsedLine(name, qty, unit);
    }

    private static int quantity(String digits) {
        long n = Long.parseLong(digits);
        return (int) Math.max(1, Math.min(n, MAX_QTY));
    }

    private static String normUnit(String raw) {
        String u = raw.toLowerCase(Locale.ROOT);
        if (u.startsWith("шт")) return "шт";
        if (u.startsWith("компл")) return "компл";
        if (u.startsWith("набор")) return "набор";
        if (u.startsWith("уп")) return "упак";
        if (u.startsWith("фл")) return "фл";
        if (u.startsWith("пар")) return "пара";
        return u;
    }
}
