package com.vladoose.nir.util;

import com.vladoose.nir.entity.MedRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Текст регистрации строки КП из записи реестра (спека 2026-10-12 §8, решение плана 3). */
class RegistrationTextTest {

    private static MedRegistry rec(LocalDate reg, LocalDate exp, Boolean unlimited) {
        MedRegistry m = new MedRegistry();
        m.setRegDate(reg);
        m.setExpirationDate(exp);
        m.setUnlimited(unlimited);
        return m;
    }

    @Test
    void unlimited() {
        assertThat(RegistrationText.of("РК-МИ (МТ)-0№023037", rec(LocalDate.of(2021, 10, 28), null, true)))
                .isEqualTo("№ РК-МИ (МТ)-0№023037 от 28.10.2021 г., бессрочно");
    }

    @Test
    void untilDate() {
        assertThat(RegistrationText.of("РК-МИ (МТ)-0№023037",
                rec(LocalDate.of(2021, 10, 28), LocalDate.of(2026, 10, 28), false)))
                .isEqualTo("№ РК-МИ (МТ)-0№023037 от 28.10.2021 г., до 28.10.2026 г.");
    }

    @Test
    void withoutDatesOrRecord_numberOnly() {
        assertThat(RegistrationText.of("РК МИ (ИМН)-0 №027522", rec(null, null, null))).isEqualTo("№ РК МИ (ИМН)-0 №027522");
        assertThat(RegistrationText.of(" РК-МИ (МТ)-0№1 ", null)).isEqualTo("№ РК-МИ (МТ)-0№1");
    }

    @Test
    void blankNumberGivesNothing() {
        assertThat(RegistrationText.of("  ", rec(LocalDate.of(2021, 1, 1), null, true))).isNull();
        assertThat(RegistrationText.of(null, null)).isNull();
    }
}
