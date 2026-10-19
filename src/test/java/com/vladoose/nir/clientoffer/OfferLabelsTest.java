package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.service.offer.OfferLabels;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Подпись КП (спека 2026-10-19 §5.1): у КП из файла номера может не быть — тогда по дате. */
class OfferLabelsTest {

    @Test
    void numberElseDate() {
        ClientOffer o = ClientOffer.builder().number(24).offerDate(LocalDate.of(2026, 9, 24)).build();
        assertThat(OfferLabels.of(o)).isEqualTo("КП № 24");
        assertThat(OfferLabels.numberOrDate(o)).isEqualTo("№ 24");
        o.setNumber(null);
        assertThat(OfferLabels.of(o)).isEqualTo("КП от 24.09.2026");
        assertThat(OfferLabels.numberOrDate(o)).isEqualTo("от 24.09.2026");
    }
}
