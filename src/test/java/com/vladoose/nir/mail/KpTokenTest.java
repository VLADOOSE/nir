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

    /**
     * Число длиннее 18 цифр меткой не считается: раньше Long.parseLong бросал NumberFormatException, и письмо с такой
     * «меткой» в теме (или в тексте возврата) не разбиралось. Следующая настоящая метка в той же строке находится.
     */
    @Test
    void tooLongNumber_notAToken_noException() {
        assertThat(KpToken.parse("Re: [КП-99999999999999999999] Запрос")).isEmpty();
        assertThat(KpToken.parse("[КП-9223372036854775808]")).isEmpty();             // больше Long.MAX_VALUE
        assertThat(KpToken.parse("[КП-999999999999999999]")).contains(999999999999999999L);
        assertThat(KpToken.parse("Fwd: [КП-99999999999999999999] и [КП-5]")).contains(5L);
    }
}
