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
    void excerpt_cutsOnWordBoundary_withEllipsis() {
        String t = MailText.excerpt("один два три четыре пять", 12);
        assertThat(t).isEqualTo("один два…");
        assertThat(t.length()).isLessThanOrEqualTo(12);
        assertThat(MailText.excerpt("коротко", 100)).isEqualTo("коротко");
    }

    @Test
    void normalize_collapsesBlankLinesAndNbsp() {
        assertThat(MailText.normalize("\n\nа\u00A0\u00A0б\n\n\n\nв  \n")).isEqualTo("а б\n\nв");
    }
}
