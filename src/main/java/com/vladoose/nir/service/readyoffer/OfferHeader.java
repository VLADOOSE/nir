package com.vladoose.nir.service.readyoffer;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Номер и дата КП из текста документа или имени файла (спека 2026-10-19 §4.4): номер — только после «Исх», «КП» или
 * «Коммерческое предложение» (не «ГКП … № 5»), дата — после номера («от …», в старом бланке «от.14.09.2026») или после «Дата:»; ДД.ММ.ГГГГ и
 * «17 апреля 2026». Несуществующая дата (31.02) — нет даты.
 */
public record OfferHeader(Integer number, LocalDate date) {

    public static final OfferHeader NONE = new OfferHeader(null, null);

    private static final List<String> MONTHS = List.of("января", "февраля", "марта", "апреля", "мая", "июня", "июля",
            "августа", "сентября", "октября", "ноября", "декабря");
    private static final String MONTH = String.join("|", MONTHS);
    private static final String DATE = "(?:\\d{1,2}\\.\\d{1,2}\\.\\d{4}|«?\\d{1,2}»?\\s+(?:" + MONTH + ")\\s+\\d{4})";
    private static final Pattern NUMBERED = Pattern.compile("(?iu)(?:(?<!\\p{L})исх(?:одящий)?\\.?\\s*(?:№|n(?!\\p{L})|номер)?"
            + "|(?<!\\p{L})(?:кп|коммерческое\\s+предложение)\\s*№)\\s*(?<n>\\d{1,7})(?:\\s*г?\\.?\\s*от\\.?\\s*(?<d>" + DATE + "))?");
    private static final Pattern DATED = Pattern.compile("(?iu)(?<!\\p{L})дата\\s*:?\\s*(?<d>" + DATE + ")");
    private static final Pattern NUMERIC = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})");
    private static final Pattern WORDS = Pattern.compile("(?iu)(\\d{1,2})»?\\s+(" + MONTH + ")\\s+(\\d{4})");

    public static OfferHeader find(List<String> lines) {
        Integer number = null;
        LocalDate date = null;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.replace(' ', ' ');
            if (number == null) {
                Matcher m = NUMBERED.matcher(line);
                if (m.find()) {
                    int n = Integer.parseInt(m.group("n"));
                    if (n > 0) {
                        number = n;
                        if (date == null && m.group("d") != null) date = date(m.group("d"));
                    }
                }
            }
            if (date == null) {
                Matcher m = DATED.matcher(line);
                if (m.find()) date = date(m.group("d"));
            }
            if (number != null && date != null) break;
        }
        return new OfferHeader(number, date);
    }

    public static LocalDate date(String s) {
        try {
            Matcher n = NUMERIC.matcher(s);
            if (n.find()) return LocalDate.of(Integer.parseInt(n.group(3)), Integer.parseInt(n.group(2)), Integer.parseInt(n.group(1)));
            Matcher w = WORDS.matcher(s);
            if (w.find()) {
                return LocalDate.of(Integer.parseInt(w.group(3)), MONTHS.indexOf(w.group(2).toLowerCase(Locale.ROOT)) + 1,
                        Integer.parseInt(w.group(1)));
            }
        } catch (DateTimeException e) {
            return null;
        }
        return null;
    }
}
