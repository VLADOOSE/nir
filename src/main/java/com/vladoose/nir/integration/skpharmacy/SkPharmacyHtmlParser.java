package com.vladoose.nir.integration.skpharmacy;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсер HTML портала fms.ecc.kz (СК-Фармация) — Jsoup. Чистый, юнит-тестируется на фикстурах.
 * Селекторы привязаны к текущей вёрстке; регресс ловят фикстур-тесты. Ошибки строки → строка пропущена.
 */
public final class SkPharmacyHtmlParser {

    private SkPharmacyHtmlParser() {}

    private static final Pattern ANN_ID = Pattern.compile("announce/index/(\\d+)");

    /** searchanno: table.table-bordered, строки данных по 10 td. */
    public static List<SkAnnounce> parseSearch(String html) {
        List<SkAnnounce> out = new ArrayList<>();
        if (html == null || html.isBlank()) return out;
        Document doc = Jsoup.parse(html);
        for (Element tr : doc.select("table.table-bordered tr")) {
            Elements tds = tr.select("td");
            if (tds.size() < 10) continue;                       // заголовок (th) / чужая строка
            Element link = tr.selectFirst("a[href*=announce/index/]");
            if (link == null) continue;
            Matcher m = ANN_ID.matcher(link.attr("href"));
            if (!m.find()) continue;
            out.add(new SkAnnounce(m.group(1), txt(tds, 0), txt(tds, 1), txt(tds, 2), txt(tds, 3),
                    txt(tds, 4), txt(tds, 5), txt(tds, 6), intOrNull(tds, 7), moneyOrNull(tds, 8), txt(tds, 9)));
        }
        return out;
    }

    /** Вкладка lots: строки лотов по ≥6 td; Jsoup-индексы: [0]№ [1]код [2]наименование [3]цена-ед [4]кол-во. */
    public static List<SkLot> parseLots(String html) {
        List<SkLot> out = new ArrayList<>();
        if (html == null || html.isBlank()) return out;
        Document doc = Jsoup.parse(html);
        for (Element tr : doc.select("table tr")) {
            Elements tds = tr.select("td");
            if (tds.size() < 6) continue;
            if (!txt(tds, 0).matches("\\d+")) continue;          // строка лота начинается с номера
            String name = txt(tds, 2);
            if (name.isBlank()) continue;
            out.add(new SkLot(txt(tds, 1), name, moneyOrNull(tds, 3), intOrNull(tds, 4)));
        }
        return out;
    }

    private static String txt(Elements tds, int i) { return i < tds.size() ? tds.get(i).text().trim() : ""; }

    private static Integer intOrNull(Elements tds, int i) {
        String s = txt(tds, i).replaceAll("[^0-9]", "");
        try { return s.isBlank() ? null : Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    /** fms формат: пробелы-тысячи + точка-десятичная («15 085 999 992.00»). */
    private static BigDecimal moneyOrNull(Elements tds, int i) {
        String s = txt(tds, i).replaceAll("[^0-9.]", "");
        if (s.isBlank()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }
}
