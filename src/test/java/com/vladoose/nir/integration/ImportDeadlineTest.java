package com.vladoose.nir.integration;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** B2: срок приёма заявок с площадки — с временем, а не только датой. */
class ImportDeadlineTest {

    @Test void parse_dateAndTime() {
        // goszakup v3 «endDate» и лента СК-Фармации — «ГГГГ-ММ-ДД ЧЧ:ММ:СС» по местному времени
        assertThat(ImportDeadline.parse("2026-10-09 10:25:00")).isEqualTo(LocalDateTime.of(2026, 10, 9, 10, 25));
        assertThat(ImportDeadline.parse("2026-10-09T10:25:00")).isEqualTo(LocalDateTime.of(2026, 10, 9, 10, 25));
        assertThat(ImportDeadline.parse(" 2026-07-13 09:00 ")).isEqualTo(LocalDateTime.of(2026, 7, 13, 9, 0));
        assertThat(ImportDeadline.parse("2026-10-07 12:19:54")).isEqualTo(LocalDateTime.of(2026, 10, 7, 12, 19, 54));
    }

    @Test void parse_withoutTimeOrGarbage_isNull() {
        assertThat(ImportDeadline.parse("2026-10-09")).isNull();
        assertThat(ImportDeadline.parse(null)).isNull();
        assertThat(ImportDeadline.parse("")).isNull();
        assertThat(ImportDeadline.parse("09.10.2026 10:25")).isNull();
        assertThat(ImportDeadline.parse("2026-13-09 10:25:00")).isNull();
        assertThat(ImportDeadline.parse("2026-10-09 25:00:00")).isNull();
    }

    @Test void passed_withTime_comparesTime() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 9, 10, 25);
        LocalDate day = at.toLocalDate();
        assertThat(ImportDeadline.passed(day, at, LocalDateTime.of(2026, 10, 9, 14, 10))).isTrue();
        assertThat(ImportDeadline.passed(day, at, LocalDateTime.of(2026, 10, 9, 10, 25))).as("ровно в срок приём закрыт").isTrue();
        assertThat(ImportDeadline.passed(day, at, LocalDateTime.of(2026, 10, 9, 10, 24))).isFalse();
    }

    @Test void passed_dateOnly_wholeDayStillOpen() {
        LocalDate day = LocalDate.of(2026, 10, 9);
        assertThat(ImportDeadline.passed(day, null, LocalDateTime.of(2026, 10, 9, 23, 59))).isFalse();
        assertThat(ImportDeadline.passed(day, null, LocalDateTime.of(2026, 10, 10, 0, 0))).isTrue();
        assertThat(ImportDeadline.passed(null, null, LocalDateTime.of(2026, 10, 10, 0, 0))).isFalse();
    }
}
