package com.vladoose.nir.integration.whatsapp;

import com.vladoose.nir.integration.greenapi.FakeGreenApiClient;
import com.vladoose.nir.integration.greenapi.GreenApiSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class WhatsappSourceConfigTest {

    final GreenApiSource green = new GreenApiSource(new FakeGreenApiClient(), 5, 300_000, 3_600_000);

    @Test
    void greenApiIsChosenWhateverTheCaseAndSpaces() {
        assertThat(WhatsappSourceConfig.select(" GreenAPI ", green)).isSameAs(green);
    }

    /** Опечатка не роняет АИС и не превращается молча в другой шлюз: приём стоит, строка объясняет. */
    @Test
    void typoStopsIntakeWithHint() {
        WhatsappSource s = WhatsappSourceConfig.select("grenapi", green);

        assertThat(s.isConfigured()).isFalse();
        assertThat(s.configHint()).contains("WHATSAPP_PROVIDER").contains("grenapi");
        assertThat(s.next()).isNull();
    }
}
