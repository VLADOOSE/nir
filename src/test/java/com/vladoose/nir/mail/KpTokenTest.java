package com.vladoose.nir.mail;

import com.vladoose.nir.util.KpToken;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KpTokenTest {

    @Test
    void roundTripAndParse() {
        assertThat(KpToken.subjectToken(42L)).isEqualTo("[КП-42]");
        assertThat(KpToken.parse(KpToken.subjectToken(42L))).contains(42L);
        assertThat(KpToken.parse("Re: Запрос КП [КП-7] от поставщика")).contains(7L);
        assertThat(KpToken.parse("Просто письмо без токена")).isEmpty();
        assertThat(KpToken.parse(null)).isEmpty();
    }
}
