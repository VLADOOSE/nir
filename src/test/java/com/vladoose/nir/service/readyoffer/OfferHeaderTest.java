package com.vladoose.nir.service.readyoffer;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Номер и дата КП из текста документа и имени файла (спека 2026-10-19 §4.4). */
class OfferHeaderTest {

    static OfferHeader of(String... lines) {
        return OfferHeader.find(List.of(lines));
    }

    @Test
    void formatsOfFatherAndAis() {
        assertThat(of("Исх № 24 от 17 апреля  2026 г.")).isEqualTo(new OfferHeader(24, LocalDate.of(2026, 4, 17)));
        assertThat(of("Исх. № 481 от 07.10.2026 г.")).isEqualTo(new OfferHeader(481, LocalDate.of(2026, 10, 7)));
        assertThat(of("КП № 481 от 07.10.2026 – Клиника.pdf")).isEqualTo(new OfferHeader(481, LocalDate.of(2026, 10, 7)));
        assertThat(of("Коммерческое предложение № 7 от 01.10.2026")).isEqualTo(new OfferHeader(7, LocalDate.of(2026, 10, 1)));
        assertThat(of("Дата: 24.09.2026 г.", "Главному врачу")).isEqualTo(new OfferHeader(null, LocalDate.of(2026, 9, 24)));
    }

    @Test
    void numberOnlyAfterIskhOrKp_badDateIsNone() {
        assertThat(of("ГКП на ПХВ «Городская поликлиника № 5»", "ГКП № 5 от 01.01.2026")).isEqualTo(OfferHeader.NONE);
        assertThat(of("Исх № 3 от 31.02.2026")).isEqualTo(new OfferHeader(3, null));
        assertThat(of("КП West-Med 24.09.2026.docx")).isEqualTo(OfferHeader.NONE);
    }
}
