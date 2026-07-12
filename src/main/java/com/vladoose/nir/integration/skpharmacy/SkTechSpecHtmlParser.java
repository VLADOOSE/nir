package com.vladoose.nir.integration.skpharmacy;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсер ТЗ-страниц СК-Фармации (fms.ecc.kz) — Jsoup, чистый, тестируется на фикстурах.
 * Вкладка documents: строка «Техническая спецификация» → кнопка actionModalShowFiles(annId, docReqId).
 * Модалка actionAjaxModalShowFiles: таблица «Номер лота | Документ(ссылка) | …» → per-lot PDF.
 * Селекторы привязаны к вёрстке ЦЭФ; регресс ловят фикстур-тесты.
 */
public final class SkTechSpecHtmlParser {

    private SkTechSpecHtmlParser() {}

    private static final String BASE_URI = "https://fms.ecc.kz";
    private static final String TECHSPEC_TYPE = "Техническая спецификация";
    private static final Pattern SHOW_FILES = Pattern.compile("actionModalShowFiles\\(\\s*\\d+\\s*,\\s*(\\d+)\\s*\\)");

    /** Вкладка documents: id требования «Техническая спецификация» из onclick кнопки «Перейти»; нет строки → null. */
    public static String parseTechSpecDocReqId(String documentsHtml) {
        if (documentsHtml == null || documentsHtml.isBlank()) return null;
        Document doc = Jsoup.parse(documentsHtml);
        for (Element tr : doc.select("tr")) {
            Elements tds = tr.select("td");
            if (tds.isEmpty()) continue;                                   // строка-заголовок (th)
            if (!TECHSPEC_TYPE.equalsIgnoreCase(tds.get(0).text().trim())) continue;
            Element btn = tr.selectFirst("button[onclick]");
            if (btn == null) continue;
            Matcher m = SHOW_FILES.matcher(btn.attr("onclick"));
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** Модалка ТЗ: по строке на лот — код лота (col 0) + ссылка на PDF (a[href*=download_file]); прочее пропускаем. */
    public static List<SkTechSpecRef> parseModal(String modalHtml) {
        List<SkTechSpecRef> out = new ArrayList<>();
        if (modalHtml == null || modalHtml.isBlank()) return out;
        Document doc = Jsoup.parse(modalHtml, BASE_URI);
        for (Element tr : doc.select("table tr")) {
            Elements tds = tr.select("td");
            if (tds.isEmpty()) continue;                                   // заголовок (th)
            Element a = tr.selectFirst("a[href*=download_file]");          // «Документ»; подпись — download_cms, не ловится
            if (a == null) continue;
            String lotCode = tds.get(0).text().trim();
            if (lotCode.isBlank()) continue;
            String url = a.absUrl("href");
            if (url == null || url.isBlank()) url = a.attr("href");
            out.add(new SkTechSpecRef(lotCode, url, a.text().trim()));
        }
        return out;
    }
}
