package com.vladoose.nir.service.mail;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MailTextTest {

    private static ParsedMail mail(String text, String html) {
        return new ParsedMail(1, null, "s@x.kz", "s@x.kz", "Тема", OffsetDateTime.now(), "text/plain",
                Map.of(), text, html, List.of(), null, null, null);
    }

    @Test
    void htmlToText_dropsStyleAndScript_brAndBlocksBreakLines() {
        String t = MailText.htmlToText("<style>.a{x:1}</style><script>var a;</script><div>Строка 1<br>Строка 2</div><p>Абзац</p>");
        assertThat(t).isEqualTo("Строка 1\nСтрока 2\nАбзац");
    }

    @Test
    void htmlToText_tableCellsSeparated_rowsOnOwnLines() {
        // пробела мало: «2» и «1 500 000 тг» слились бы для распознавания цены в одно число «21 500 000»
        String t = MailText.htmlToText(
                "<table><tr><td>2</td><td>1 500 000 тг</td></tr><tr><td>Срок</td><td>30 дней</td></tr></table>");
        assertThat(t).isEqualTo("2 | 1 500 000 тг\nСрок | 30 дней");
    }

    @Test
    void htmlToText_breaksBeforeBlockAndAtHr() {
        // так пишут редакторы Chrome/Gmail: строка, а следующая — блоком, без разрыва перед ним
        assertThat(MailText.htmlToText("line1<div>line2</div>")).isEqualTo("line1\nline2");
        assertThat(MailText.htmlToText("Количество 2<hr>1 500 000 тг")).isEqualTo("Количество 2\n1 500 000 тг");
    }

    @Test
    void htmlToText_chromeNestedDiv_numbersNotGlued() {
        String t = MailText.htmlToText("<div dir=\"ltr\">Количество: 2<div>1 500 000 тг за единицу</div></div>");
        assertThat(t).contains("Количество: 2\n1 500 000 тг за единицу");
    }

    @Test
    void htmlToText_outlookCellsWithParagraphs_rowStaysOnOneLine() {
        // Outlook кладёт в каждую ячейку абзац; второй вариант — с переводами строк между тегами
        String compact = "<table><tr><td><p>2</p></td><td><p>1 500 000 тг</p></td></tr></table>";
        String indented = "<table>\n<tr>\n<td><p>2</p></td>\n<td><p>1 500 000 тг</p></td>\n</tr>\n</table>";
        assertThat(MailText.htmlToText(compact)).doesNotContain("21 500 000").contains("1 500 000 тг")
                .isEqualTo("2 | 1 500 000 тг");
        assertThat(MailText.htmlToText(indented)).doesNotContain("21 500 000").contains("1 500 000 тг")
                .isEqualTo("2 | 1 500 000 тг");
    }

    @Test
    void replyText_cutsQuotedOriginal_plain() {
        String t = MailText.replyText(mail("Цена 10 000 тг.\n\n> Здравствуйте! Просим КП\n> по лоту", ""));
        assertThat(t).isEqualTo("Цена 10 000 тг.");
    }

    @Test
    void replyText_dropsHtmlBlockquote() {
        String t = MailText.replyText(mail("", "<div>Отказ, не поставляем</div><blockquote>Наше письмо: просим КП</blockquote>"));
        assertThat(t).isEqualTo("Отказ, не поставляем");
    }

    @Test
    void replyText_htmlTable_cellsStayOnOneLine() {
        // отрывок уведомления строится из replyText: и в нём строка таблицы — одна строка текста
        String t = MailText.replyText(mail("", "<table><tr><td>2</td><td>1 500 000 тг</td></tr></table>"
                + "<blockquote>Наше письмо: просим КП</blockquote>"));
        assertThat(t).isEqualTo("2 | 1 500 000 тг");
    }

    @Test
    void replyText_plainAngleBrackets_keepTextAndStillCutQuote() {
        // «<» и «>» в готовом тексте — не теги: снятие тегов съело бы всё от «<30» до маркера цитаты «> »
        String t = MailText.replyText(
                mail("Срок поставки <30 дней.\nЦена 1 500 000 тг\n\n> Здравствуйте! Просим КП", ""));
        assertThat(t).isEqualTo("Срок поставки <30 дней.\nЦена 1 500 000 тг");
    }

    @Test
    void replyText_htmlEscapedAngleBrackets_keepText() {
        String t = MailText.replyText(mail("",
                "<p>Срок поставки &lt;30 дней</p><p>Цена 1 500 000 тг</p><p>Менеджер Анна &lt;anna@x.kz&gt;</p>"));
        assertThat(t).contains("Срок поставки <30 дней").contains("Цена 1 500 000 тг")
                .contains("Менеджер Анна <anna@x.kz>");
    }

    @Test
    void excerpt_cutsOnWordBoundary_withEllipsis() {
        String t = MailText.excerpt("один два три четыре пять", 12);
        assertThat(t).isEqualTo("один два…");
        assertThat(t.length()).isLessThanOrEqualTo(12);
        assertThat(MailText.excerpt("коротко", 100)).isEqualTo("коротко");
    }

    @Test
    void excerpt_hardCut_doesNotSplitSurrogatePair() {
        // 😀 — две половинки суррогатной пары на индексах 8 и 9; пробелов нет, поэтому срез жёсткий — по max - 1 = 9,
        // ровно между половинками
        String t = MailText.excerpt("абвгдежз\uD83D\uDE00ийклмнопрст", 10);
        assertNoLoneSurrogates(t);
        assertThat(t).endsWith("…");
        assertThat(t.length()).isLessThanOrEqualTo(10);
        assertThat(t).isEqualTo("абвгдежз…");
    }

    @Test
    void safeCut_doesNotSplitSurrogatePair() {
        // 😀 — на индексах 3 и 4: срез по 4 пришёлся бы ровно между половинками
        String s = "абв\uD83D\uDE00где";
        String cut = MailText.safeCut(s, 4);
        assertNoLoneSurrogates(cut);
        assertThat(cut).isEqualTo("абв");
        assertThat(MailText.safeCut(s, 5)).isEqualTo("абв\uD83D\uDE00");
        assertThat(MailText.safeCut(s, 100)).isSameAs(s);
    }

    /** Половинка суррогатной пары без второй — невалидный текст: такое уведомление Telegram не примет. */
    private static void assertNoLoneSurrogates(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertThat(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)))
                        .as("старшая половина суррогатной пары без младшей, позиция %d", i).isTrue();
                i++;
            } else {
                assertThat(Character.isLowSurrogate(c))
                        .as("младшая половина суррогатной пары без старшей, позиция %d", i).isFalse();
            }
        }
    }

    @Test
    void normalize_collapsesBlankLinesAndNbsp() {
        assertThat(MailText.normalize("\n\nа\u00A0\u00A0б\n\n\n\nв  \n")).isEqualTo("а б\n\nв");
    }
}
