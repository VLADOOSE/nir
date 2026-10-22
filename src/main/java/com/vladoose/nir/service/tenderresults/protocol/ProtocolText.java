package com.vladoose.nir.service.tenderresults.protocol;

import com.vladoose.nir.util.PdfTextExtractor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Протокол итогов → строки (спека 2026-10-22-tender-results-market-prices §5): PDF — текст PDFBox по строкам; HTML — заголовки,
 * абзацы (по &lt;br&gt;), подписи таблиц и строки таблиц ячейками через « | ». Пробелы, неразрывные пробелы и табуляции — одним
 * пробелом. Русская часть — с первой строки «Протокол об итогах» (казахская идёт раньше).
 */
public final class ProtocolText {

    /** &lt;br&gt; внутри абзаца — разрыв строки; символ, который Jsoup пробелом не считает и не схлопывает. */
    private static final String BR = "\u2028";

    private ProtocolText() {}

    /** null — не PDF и не HTML или PDF не читается. */
    public static List<String> lines(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return null;
        if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
            String text = PdfTextExtractor.extract(bytes);
            return text == null ? null : textLines(text);
        }
        String html = new String(bytes, StandardCharsets.UTF_8);
        String low = html.toLowerCase(Locale.ROOT);
        if (!low.contains("<table") && !low.contains("<html")) return null;
        Document doc = Jsoup.parse(html.replaceAll("(?i)<br\\s*/?>", BR));
        List<String> out = new ArrayList<>();
        for (Element e : doc.body().select("h1, h2, h3, p, caption, tr")) {
            if (e.tagName().equals("tr")) {
                String row = e.children().stream().filter(c -> c.tagName().equals("td") || c.tagName().equals("th"))
                        .map(c -> clean(c.text())).collect(Collectors.joining(" | "));
                if (!row.replace("|", "").isBlank()) out.add(row);
            } else {
                for (String part : e.text().split(BR)) {
                    String t = clean(part);
                    if (!t.isEmpty()) out.add(t);
                }
            }
        }
        return out;
    }

    /** Текст (PDFBox) → строки без пустых. */
    public static List<String> textLines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\\r?\\n")) {
            String t = clean(l);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** С первой строки «Протокол об итогах»; её нет — все строки. */
    public static List<String> russian(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("Протокол об итогах")) return lines.subList(i, lines.size());
        }
        return lines;
    }

    static String clean(String s) {
        return s == null ? "" : s.replace(BR, " ").replaceAll("[\\s\\u00A0]+", " ").trim();
    }
}
