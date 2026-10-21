package com.vladoose.nir.tgbot;

import com.vladoose.nir.service.tgbot.BotLetterActions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** «Ответить до» в письме из бота (спека 2026-10-21-tender-telegram-bot §7). */
class BotReplyByTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    @Test
    void twoDaysBefore_notBeforeTomorrow_notAfterDeadline_noDeadline3Days() {
        assertThat(BotLetterActions.replyBy(LocalDate.of(2026, 10, 31), TODAY)).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(BotLetterActions.replyBy(LocalDate.of(2026, 10, 22), TODAY)).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(BotLetterActions.replyBy(TODAY, TODAY)).isEqualTo(TODAY);
        assertThat(BotLetterActions.replyBy(null, TODAY)).isEqualTo(LocalDate.of(2026, 10, 24));
    }
}
