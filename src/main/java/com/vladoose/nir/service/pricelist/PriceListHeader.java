package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.PriceListColumn;
import com.vladoose.nir.service.readyoffer.OfferHeader;
import com.vladoose.nir.service.supplierreply.MoneyText;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Шапка прайса из текста вне таблицы (спека §5.2 п. 4): дата и «действует до» — только в строках про прайс или цены
 * (срок РУ «Действителен до 03.05.2028» — не срок прайса), иначе дата из имени файла; «розничный»; условия поставки.
 */
public record PriceListHeader(LocalDate listDate, LocalDate validTo, boolean retail, String terms) {

    static final int TERMS_MAX = 1000;
    private static final String MONTH = "(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)";
    private static final String DATE = "(?:\\d{1,2}\\.\\d{1,2}\\.\\d{4}|\\d{1,2}\\s+" + MONTH + "\\s+\\d{4})";
    private static final Pattern FROM = Pattern.compile("(?iu)(?<!\\p{L})(?:с|от)\\.?\\s*(" + DATE + ")");
    private static final Pattern TO = Pattern.compile("(?iu)(?<!\\p{L})(?:по|до)\\s*(" + DATE + ")");
    private static final Pattern PRICE_LINE = Pattern.compile("(?iu)прайс|(?<!\\p{L})цен(?:а|ы|у)?(?!\\p{L})|действительн|актуал");
    private static final Pattern FILE_DATE = Pattern.compile("(?<!\\d)(\\d{2})[._-](\\d{2})[._-](\\d{4})(?!\\d)");
    private static final Pattern RETAIL = Pattern.compile("(?iu)розничн|(?<!\\p{L})ррц(?!\\p{L})");
    private static final Pattern TERM = Pattern.compile("(?iu)^\\s*[•·*]|цены указаны|предоплат|отгрузк|доставк"
            + "|(?<!\\p{L})(?:fca|exw|dap|ddp|cpt|cip)(?!\\p{L})|ст-1|поверк|гаранти");

    public static PriceListHeader of(List<String> lines, String fileName) {
        LocalDate from = null, to = null;
        boolean retail = false;
        List<String> terms = new ArrayList<>();
        for (String raw : lines == null ? List.<String>of() : lines) {
            if (raw == null) continue;
            String line = raw.replace('\u00A0', ' ').strip();
            if (line.isEmpty()) continue;
            if (RETAIL.matcher(line).find()) retail = true;
            if (PRICE_LINE.matcher(line).find()) {
                if (from == null) {
                    Matcher m = FROM.matcher(line);
                    if (m.find()) from = OfferHeader.date(m.group(1));
                }
                if (to == null) {
                    Matcher m = TO.matcher(line);
                    if (m.find()) to = OfferHeader.date(m.group(1));
                }
            }
            if (TERM.matcher(line).find()) terms.add(line);
        }
        if (from == null && fileName != null) {
            Matcher m = FILE_DATE.matcher(fileName);
            if (m.find()) {
                try {
                    from = LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
                } catch (DateTimeException ignored) {
                    // «31_02_2026» — не дата
                }
            }
        }
        return new PriceListHeader(from, to, retail, joinTerms(terms));
    }

    private static String joinTerms(List<String> terms) {
        if (terms.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (String t : terms) {
            if (b.length() + t.length() + 1 > TERMS_MAX) break;
            if (!b.isEmpty()) b.append('\n');
            b.append(t);
        }
        return b.isEmpty() ? null : b.toString();
    }

    /** Валюта по тексту: одна — она, разные (или суммы в тысячах) — MIXED, не названа — null. */
    public static String currency(List<String> lines) {
        Set<String> codes = new TreeSet<>();
        for (String l : lines == null ? List.<String>of() : lines) codes.addAll(MoneyText.textCurrencies(l));
        if (codes.isEmpty()) return null;
        return codes.size() == 1 ? codes.iterator().next() : MoneyText.MIXED;
    }

    /** Колонка «наша закупка» по умолчанию (спека §5.2 п. 5): «дилер» → «со скидкой» → «опт» → самая низкая цена в большинстве строк. */
    public static Integer defaultColumn(List<PriceListColumn> columns, List<ParsedPriceRow> rows) {
        if (columns == null || columns.isEmpty()) return null;
        if (columns.size() == 1) return columns.get(0).index();
        for (String key : List.of("дилер", "со скидк", "с учетом скидк", "опт")) {
            for (PriceListColumn c : columns) {
                String h = c.header() == null ? "" : c.header().toLowerCase(Locale.ROOT).replace('ё', 'е');
                if (h.contains(key)) return c.index();
            }
        }
        int[] wins = new int[columns.size()];
        for (ParsedPriceRow r : rows) {
            int best = -1;
            for (int i = 0; i < r.prices().size() && i < wins.length; i++) {
                BigDecimal p = r.prices().get(i);
                if (p != null && (best < 0 || p.compareTo(r.prices().get(best)) < 0)) best = i;
            }
            if (best >= 0) wins[best]++;
        }
        int arg = 0;
        for (int i = 1; i < wins.length; i++) if (wins[i] > wins[arg]) arg = i;
        return columns.get(arg).index();
    }
}
