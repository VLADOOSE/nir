package com.vladoose.nir.service.mail;

import com.vladoose.nir.util.EmailReplyText;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/** Текст письма для людей: HTML → текст, ответ без цитаты нашего письма, отрывок. */
public final class MailText {

    /** Блочные элементы: перевод строки и перед ними, и после — как их показывает почтовый клиент. */
    private static final Set<String> BLOCKS = Set.of("p", "div", "tr", "li", "h1", "h2", "h3", "h4", "h5", "h6",
            "table", "blockquote", "hr");
    /** Пробельный текст прямо внутри этих элементов — отступы исходника между строками и ячейками, не текст письма. */
    private static final Set<String> TABLE_PARTS = Set.of("table", "thead", "tbody", "tfoot", "tr");

    private MailText() {}

    /**
     * HTML → текст. Блоки style/script/head выбрасываются: регулярка {@link EmailReplyText} оставила бы CSS писем
     * Outlook в начале текста. br — перевод строки; блочные элементы и hr — перевод строки и ПЕРЕД собой, и после:
     * редакторы Chrome и Gmail пишут {@code строка1<div>строка2</div>}, и без разрыва перед блоком «Количество: 2»
     * и «1 500 000 тг» слились бы в одно число. Соседние ячейки таблицы разделяются « | », строки таблицы — переводом
     * строки: пробела мало — «100» и «500 000 тг» тоже слились бы. Края текста ячейки обрезаются: абзац, который
     * Outlook кладёт в каждую ячейку, не рвёт строку таблицы.
     */
    public static String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        return toText(Jsoup.parse(html));
    }

    /**
     * Ответ без цитаты нашего письма: HTML-цитата (blockquote) выбрасывается целиком, текстовая — отрезается
     * {@link EmailReplyText#cutQuote}. Именно отрез, а не {@link EmailReplyText#stripToReply}: текст уже готовый,
     * {@code <} и {@code >} в нём — обычные символы ({@code срок <30 дней}, {@code Анна <anna@x.kz>}), а снятие
     * «тегов» съело бы всё между ними вместе с маркером цитаты. Документ переводится в текст сам, а не через
     * {@code outerHtml()}: тот форматирует HTML с отступами, и после повторного разбора ячейки строки таблицы
     * разъезжались по разным строкам текста.
     */
    public static String replyText(ParsedMail m) {
        String body;
        if (!m.text().isBlank()) {
            body = m.text();
        } else {
            Document doc = Jsoup.parse(m.html());
            doc.select("blockquote").remove();
            body = toText(doc);
        }
        return normalize(EmailReplyText.cutQuote(body));
    }

    private static String toText(Document doc) {
        doc.select("style, script, head, title").remove();
        TextCollector collector = new TextCollector();
        NodeTraversor.traverse(collector, doc.body());
        return normalize(collector.out.toString());
    }

    /**
     * Отрывок не длиннее max символов: режется по границе слова, с «…»; срез — {@link #safeCut}, эмодзи пополам
     * не режет.
     */
    public static String excerpt(String s, int max) {
        String t = normalize(s);
        if (t.length() <= max) return t;
        int cut = Math.max(t.lastIndexOf(' ', max - 1), t.lastIndexOf('\n', max - 1));
        if (cut < max / 2) cut = max - 1;
        return safeCut(t, cut).strip() + "…";
    }

    /**
     * Строка не длиннее max символов {@code char}, и суррогатная пара (эмодзи) не разрезается: одиночная половинка —
     * невалидный текст, Telegram такое уведомление отклонит, и очередь встанет на нём. Через него идут все срезы
     * текста письма.
     */
    static String safeCut(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int end = Math.max(0, max);
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
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

    /**
     * Сборка текста обходом {@link NodeTraversor} — без рекурсии, глубоко вложенный HTML стек не переполнит. Перевод
     * строки перед блоком ставится, только если текст им ещё не кончается: вложенные div не плодят пустых строк.
     */
    private static final class TextCollector implements NodeVisitor {
        final StringBuilder out = new StringBuilder();
        /** Где в {@link #out} начинается текст открытых ячеек таблицы (вложенные таблицы — глубже в стеке). */
        private final Deque<Integer> cellStarts = new ArrayDeque<>();

        @Override
        public void head(Node node, int depth) {
            if (node instanceof TextNode t) {
                if (!tableIndent(t)) out.append(t.getWholeText());
            } else if (node instanceof Element e) {
                if (e.nameIs("br")) {
                    out.append('\n');
                } else if (cell(e)) {
                    if (e.previousElementSibling() != null) out.append(" | ");
                    cellStarts.push(out.length());
                } else if (BLOCKS.contains(e.normalName())) {
                    lineBreak();
                }
            }
        }

        @Override
        public void tail(Node node, int depth) {
            if (!(node instanceof Element e)) return;
            if (cell(e)) {
                int start = cellStarts.pop();
                String content = out.substring(start).strip();
                out.setLength(start);
                out.append(content);
            } else if (BLOCKS.contains(e.normalName())) {
                lineBreak();
            }
        }

        /** Перевод строки, если текст не пуст и ещё не кончается им (пробелы и табы в конце не в счёт). */
        private void lineBreak() {
            int i = out.length() - 1;
            while (i >= 0 && (out.charAt(i) == ' ' || out.charAt(i) == '\t')) i--;
            if (i >= 0 && out.charAt(i) != '\n') out.append('\n');
        }

        private static boolean cell(Element e) {
            return e.nameIs("td") || e.nameIs("th");
        }

        private static boolean tableIndent(TextNode t) {
            return t.isBlank() && t.parent() instanceof Element p && TABLE_PARTS.contains(p.normalName());
        }
    }
}
