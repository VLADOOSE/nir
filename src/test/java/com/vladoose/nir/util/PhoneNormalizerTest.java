package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNormalizerTest {

    @Test
    void kzMobileInAnyNotationBecomesE164() {
        assertThat(PhoneNormalizer.normalize("8 (777) 075-27-70")).isEqualTo("+77770752770");
        assertThat(PhoneNormalizer.normalize("+7 777 075 27 70")).isEqualTo("+77770752770");
        assertThat(PhoneNormalizer.normalize("77770752770")).isEqualTo("+77770752770");
    }

    @Test
    void tenDigitsWithoutCountryCodeGetSeven() {
        assertThat(PhoneNormalizer.normalize("777 075 27 70")).isEqualTo("+77770752770");
        // городской СПб без кода страны: 10 цифр, ведущая «8» — код города, а не межгород
        assertThat(PhoneNormalizer.normalize("812 345-67-89")).isEqualTo("+78123456789");
    }

    @Test
    void foreignAndJunkAreNotNormalized() {
        assertThat(PhoneNormalizer.normalize("+998 90 123 45 67")).isNull();
        assertThat(PhoneNormalizer.normalize("12-34")).isNull();
        assertThat(PhoneNormalizer.normalize("")).isNull();
        assertThat(PhoneNormalizer.normalize(null)).isNull();
    }

    @Test
    void last10IsTheSubscriberPart() {
        assertThat(PhoneNormalizer.last10("+77770752770")).isEqualTo("7770752770");
        assertThat(PhoneNormalizer.last10(null)).isNull();
    }
}
