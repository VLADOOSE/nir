package com.vladoose.nir.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** B2: время приёма с площадки относится только к своей дате — перенос даты в форме тендера его снимает. */
class TenderDeadlineTest {

    @Test void sameDateKeepsTime_otherDateDropsIt() {
        Tender t = new Tender();
        t.setDeadline(LocalDate.of(2026, 10, 9));
        t.setDeadlineAt(LocalDateTime.of(2026, 10, 9, 10, 25));

        t.setDeadline(LocalDate.of(2026, 10, 9));
        assertThat(t.getDeadlineAt()).as("та же дата — время площадки остаётся").isEqualTo(LocalDateTime.of(2026, 10, 9, 10, 25));

        t.setDeadline(LocalDate.of(2026, 10, 12));
        assertThat(t.getDeadlineAt()).as("оператор перенёс дату — время площадки к ней не относится").isNull();
    }
}
