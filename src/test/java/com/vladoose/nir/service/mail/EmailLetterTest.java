package com.vladoose.nir.service.mail;

import org.junit.jupiter.api.Test;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

class EmailLetterTest {

    @Test
    void fromParsedMail_quoteCut_nameAndKey() {
        ParsedMail m = mail().messageId("<abc@clinic.kz>").from("\"Айгуль Сапарова\" <aigul@clinic.kz>")
                .subject(" Запрос цены ")
                .text("Добрый день! Нужны кушетки 3 шт.\n\nOn Mon, 12 Oct 2026, info@westmed.kz wrote:\n> Здравствуйте").build();

        EmailLetter l = EmailLetter.of(m, MailFolder.INBOX, 7);

        assertThat(l.key()).isEqualTo("<abc@clinic.kz>");
        assertThat(l.subject()).isEqualTo("Запрос цены");
        assertThat(l.body()).isEqualTo("Добрый день! Нужны кушетки 3 шт.");
        assertThat(l.fromAddress()).isEqualTo("aigul@clinic.kz");
        assertThat(l.fromName()).isEqualTo("Айгуль Сапарова");
        assertThat(l.sentAt()).isEqualTo(m.receivedAt());
    }

    /** Переслали без своего текста — в переписке остаётся цитата, а не пусто. */
    @Test
    void letterThatIsAllQuote_keepsItsText() {
        ParsedMail m = mail().from("c@clinic.kz").text("> Добрый день! Повторяю запрос на кушетки").build();

        assertThat(EmailLetter.of(m, MailFolder.INBOX, 7).body()).isEqualTo("> Добрый день! Повторяю запрос на кушетки");
    }

    @Test
    void key_longMessageIdHashed_missingUsesFolderAndUid() {
        String longId = "<" + "x".repeat(120) + "@clinic.kz>";
        assertThat(EmailLetter.key(longId, MailFolder.INBOX, 7, 5)).startsWith("sha256:").hasSize(71)
                .isEqualTo(EmailLetter.key(longId, MailFolder.SENT, 9, 1));
        assertThat(EmailLetter.key(null, MailFolder.SENT, 7, 5)).isEqualTo("uid:sent:7:5");
        assertThat(EmailLetter.key("<>", MailFolder.INBOX, 7, 5)).isEqualTo("uid:inbox:7:5");
    }

    @Test
    void nameOf_cases() {
        assertThat(EmailLetter.nameOf("Иван Петров <ivan@x.kz>")).isEqualTo("Иван Петров");
        assertThat(EmailLetter.nameOf("ivan@x.kz")).isNull();
        assertThat(EmailLetter.nameOf("<ivan@x.kz>")).isNull();
    }
}
