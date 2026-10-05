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
        String t = MailText.replyText(mail("",
                "<table><tr><td>2</td><td>1 500 000 тг</td></tr></table><blockquote>Наше письмо: просим КП</blockquote>"));
        assertThat(t).isEqualTo("2 | 1 500 000 тг");
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
