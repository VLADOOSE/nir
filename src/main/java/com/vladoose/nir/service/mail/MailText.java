package com.vladoose.nir.service.mail;

import com.vladoose.nir.util.EmailReplyText;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

/** Текст письма для людей: HTML → текст, ответ без цитаты нашего письма, отрывок. */
public final class MailText {

    private MailText() {}

    /**
     * HTML → текст. Блоки style/script/head выбрасываются: регулярка {@link EmailReplyText} оставила бы CSS писем
     * Outlook в начале текста. br и блочные элементы дают переводы строк. br ЗАМЕНЯЕТСЯ переводом строки, а не
     * дополняется им: {@code wholeText()} jsoup 1.18 сам печатает br как перевод строки, и вставка рядом давала
     * пустую строку внутри абзаца.
     */
    public static String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        Document doc = Jsoup.parse(html);
        doc.select("style, script, head, title").remove();
        for (Element br : doc.select("br")) br.replaceWith(new TextNode("\n"));
        for (Element block : doc.select("p, div, tr, li, h1, h2, h3, h4, h5, h6, table, blockquote")) {
            block.after(new TextNode("\n"));
        }
        return normalize(doc.body() != null ? doc.body().wholeText() : doc.wholeText());
    }

    /** Ответ без цитаты нашего письма: HTML-цитата (blockquote) выбрасывается целиком, текстовая — {@link EmailReplyText}. */
    public static String replyText(ParsedMail m) {
        String body;
        if (!m.text().isBlank()) {
            body = m.text();
        } else {
            Document doc = Jsoup.parse(m.html());
            doc.select("blockquote").remove();
            body = htmlToText(doc.outerHtml());
        }
        return normalize(EmailReplyText.stripToReply(body));
    }

    /** Отрывок не длиннее max символов: режется по границе слова, с «…». */
    public static String excerpt(String s, int max) {
        String t = normalize(s);
        if (t.length() <= max) return t;
        int cut = Math.max(t.lastIndexOf(' ', max - 1), t.lastIndexOf('\n', max - 1));
        if (cut < max / 2) cut = max - 1;
        return t.substring(0, cut).strip() + "…";
    }

    /** NBSP → пробел, пробелы в строке схлопнуты, строки обрезаны, подряд — не больше одной пустой строки. */
    static String normalize(String s) {
        if (s == null) return "";
        String[] lines = s.replace('\u00A0', ' ').replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder();
        int blank = 0;
        for (String line : lines) {
            String l = line.replaceAll("[ \\t\\x0B\\f]+", " ").strip();
            if (l.isEmpty()) {
                blank++;
                if (blank > 1 || out.length() == 0) continue;
                out.append('\n');
            } else {
                blank = 0;
                out.append(l).append('\n');
            }
        }
        return out.toString().strip();
    }
}
