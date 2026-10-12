package com.vladoose.nir.util;

import com.vladoose.nir.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailAddressesTest {

    @Test
    void splitsByCommaSemicolonAndSpaces_dropsRepeatsIgnoringCase() {
        assertThat(EmailAddresses.parse(" sales7@ema.su; sales9@ema.su ,SALES7@ema.su  manager5@c.axion.ru "))
                .containsExactly("sales7@ema.su", "sales9@ema.su", "manager5@c.axion.ru");
    }

    @Test
    void blankIsEmpty() {
        assertThat(EmailAddresses.parse("  ")).isEmpty();
        assertThat(EmailAddresses.parse(null)).isEmpty();
        assertThat(EmailAddresses.normalize(null)).isEmpty();
    }

    @Test
    void invalidAddressIs400WithTheAddress() {
        assertThatThrownBy(() -> EmailAddresses.parse("ok@a.kz, broken@kz"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("«broken@kz»");
        assertThatThrownBy(() -> EmailAddresses.parse("Иван <ivan@a.kz>")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> EmailAddresses.parse("a@@b.kz")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void normalizeJoinsWithCommaSpace() {
        assertThat(EmailAddresses.normalize("a@x.kz;b@y.kz")).isEqualTo("a@x.kz, b@y.kz");
    }

    @Test
    void listLongerThanTheColumnIs400() {
        String many = IntStream.range(0, 20).mapToObj(i -> "manager" + i + "@company-long-domain.kz")
                .collect(Collectors.joining(","));
        assertThatThrownBy(() -> EmailAddresses.normalize(many))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("255");
    }

    @Test
    void sameIgnoresCaseAndSeparatorsAndNeverThrows() {
        assertThat(EmailAddresses.same("a@x.kz, b@y.kz", "A@X.kz;b@y.kz")).isTrue();
        assertThat(EmailAddresses.same("a@x.kz", "b@y.kz")).isFalse();
        assertThat(EmailAddresses.same("кривой адрес", "кривой адрес")).isTrue();
        assertThat(EmailAddresses.same("a@x.kz", null)).isFalse();
    }

    @Test
    void domainsOnlyForLogs() {
        assertThat(EmailAddresses.domains(List.of("a@x.kz", "b@x.kz", "c@y.kz"))).isEqualTo("@x.kz, @y.kz");
    }
}
