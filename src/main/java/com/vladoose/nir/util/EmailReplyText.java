package com.vladoose.nir.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Отрезает от письма-ответа поставщика процитированный оригинал (наше же письмо, обычно висит ниже
 * ответа) и снимает HTML — чтобы эвристики читали только САМ ответ поставщика, а не наш текст.
 * Общий примитив для {@link SupplierReplyPriceParser} и {@link SupplierReplyDeclineDetector}.
 */
public final class EmailReplyText {

    /** Начало цитаты нашего же письма — всё от маркера и ниже отбрасываем. */
    private static final Pattern QUOTE_BOUNDARY = Pattern.compile(
            "(?im)^[ \\t]*(>|On .+wrote:|From:[ \\t]|Sent:[ \\t]|Отправлено:|От кого:|-{3,}[ ]*Original)");

    /** Строка-атрибуция цитаты («… пишет:» / «On … wrote:») — mail.ru/Apple Mail не ставят "> ". */
    private static final Pattern ATTRIBUTION = Pattern.compile(
            "(?im)^.{0,120}?\\b(пишет|wrote|schrieb)\\s*:[ \\t]*$");

    private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]+>");

    private EmailReplyText() {}

    /** HTML снят + всё от начала цитаты нашего письма отброшено. null → "". */
    public static String stripToReply(String rawBody) {
        if (rawBody == null) return "";
        return cutQuote(stripHtml(rawBody));
    }

    /**
     * Только отрез цитаты нашего письма — для уже готового текста: без снятия HTML. В готовом тексте {@code <} и
     * {@code >} — обычные символы ({@code срок <30 дней}, {@code Анна <anna@x.kz>}), и снятие «тегов» съело бы всё
     * между ними вместе с маркером цитаты. null → "".
     */
    public static String cutQuote(String text) {
        if (text == null) return "";
        int cut = firstMatchStart(QUOTE_BOUNDARY, text);
        int attr = firstMatchStart(ATTRIBUTION, text);
        if (attr >= 0 && (cut < 0 || attr < cut)) cut = attr;
        return cut >= 0 ? text.substring(0, cut) : text;
    }

    private static String stripHtml(String s) {
        if (s.indexOf('<') < 0) return s;
        String noTags = HTML_TAG.matcher(s).replaceAll(" ");
        return noTags.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replaceAll("&#\\d+;", " ");
    }

    private static int firstMatchStart(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.start() : -1;
    }
}
