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
        return stripQuote(stripHtml(rawBody));
    }

    private static String stripHtml(String s) {
        if (s.indexOf('<') < 0) return s;
        String noTags = HTML_TAG.matcher(s).replaceAll(" ");
        return noTags.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replaceAll("&#\\d+;", " ");
    }

    private static String stripQuote(String s) {
        int cut = firstMatchStart(QUOTE_BOUNDARY, s);
        int attr = firstMatchStart(ATTRIBUTION, s);
        if (attr >= 0 && (cut < 0 || attr < cut)) cut = attr;
        return cut >= 0 ? s.substring(0, cut) : s;
    }

    private static int firstMatchStart(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.start() : -1;
    }
}
