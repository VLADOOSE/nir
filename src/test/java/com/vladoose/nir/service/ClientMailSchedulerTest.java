package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.service.mail.ClientMailSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClientMailSchedulerTest {

    ClientMailReceiveService service = mock(ClientMailReceiveService.class);

    @AfterEach void clear() { MarketContext.clear(); }

    static ClientMailSettings settings(boolean enabled) {
        return new ClientMailSettings(enabled, "imap.mail.ru", 993, "info@westmed.kz", "pw", "imaps", "KZ", 7, 25,
                "*.mail.ru", "info@westmed.kz", "zakup@westmed.kz");
    }

    @Test
    void pass_setsMailboxMarket_clearsAfter() {
        AtomicReference<Market> during = new AtomicReference<>();
        when(service.poll()).thenAnswer(inv -> { during.set(MarketContext.get()); return null; });

        new ClientMailScheduler(service, settings(true)).pass();

        assertThat(during.get()).isEqualTo(Market.KZ);
        assertThat(MarketContext.get()).isEqualTo(Market.RF);   // очищен: дефолт RF
    }

    @Test
    void pass_runtimeFailureClearsMarket() {
        when(service.poll()).thenThrow(new IllegalStateException("boom"));

        new ClientMailScheduler(service, settings(true)).pass();

        assertThat(MarketContext.get()).isEqualTo(Market.RF);
    }

    @Test
    void disabled_tickDoesNothing() {
        new ClientMailScheduler(service, settings(false)).tick();
        verifyNoInteractions(service);
    }
}
