package com.vladoose.nir.integration.whatsapp;

import com.vladoose.nir.integration.greenapi.FakeGreenApiClient;
import com.vladoose.nir.integration.greenapi.GreenApiSource;
import com.vladoose.nir.integration.waha.WahaInboxSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.*;

class WhatsappSourceConfigTest {

    final GreenApiSource green = new GreenApiSource(new FakeGreenApiClient(), 5, 300_000, 3_600_000);
    final WahaInboxSource waha = Mockito.mock(WahaInboxSource.class);

    @Test
    void greenApiIsChosenWhateverTheCaseAndSpaces() {
        assertThat(WhatsappSourceConfig.select(" GreenAPI ", green, waha)).isSameAs(green);
    }

    @Test
    void wahaIsChosenByName() {
        assertThat(WhatsappSourceConfig.select("waha", green, waha)).isSameAs(waha);
    }

    /** Опечатка не роняет АИС и не превращается молча в другой шлюз: приём стоит, строка объясняет. */
    @Test
    void typoStopsIntakeWithHint() {
        WhatsappSource s = WhatsappSourceConfig.select("grenapi", green, waha);

        assertThat(s.isConfigured()).isFalse();
        assertThat(s.configHint()).contains("WHATSAPP_PROVIDER").contains("grenapi");
        assertThat(s.next()).isNull();
    }
}
