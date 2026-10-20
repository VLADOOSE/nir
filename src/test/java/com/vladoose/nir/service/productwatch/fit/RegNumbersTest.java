package com.vladoose.nir.service.productwatch.fit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegNumbersTest {
    @Test
    void normalize() {
        assertThat(RegNumbers.normalize("  РК-МТ-5№022493 ")).isEqualTo("РК-МТ-5№022493");
        assertThat(RegNumbers.normalize("РК МИ  (МТ)-0№023841")).isEqualTo("РК МИ (МТ)-0№023841");
        assertThat(RegNumbers.normalize("РК МИ (МТ)-0№023841")).isEqualTo("РК МИ (МТ)-0№023841");
        assertThat(RegNumbers.normalize("  ")).isNull();
        assertThat(RegNumbers.normalize(null)).isNull();
    }
}
