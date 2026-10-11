package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2: сроки площадок — местное время рынка, а прод живёт в UTC. Закрытие просроченных сравнивает срок с
 * «сейчас» по часам своего рынка: KZ — Уральск (UTC+5), RF — Самара (UTC+4).
 */
class TenderStatusSchedulerTest {

    @Test
    void eachMarketIsClosedByItsOwnLocalTime() {
        List<String> calls = new ArrayList<>();
        TenderStatusService recorder = new TenderStatusService(null) {
            @Override
            public int completeExpired(LocalDateTime now) {
                calls.add(MarketContext.get() + " " + now);
                return 0;
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T09:10:00Z"), ZoneOffset.UTC);

        new TenderStatusScheduler(recorder, clock).completeExpiredForAllMarkets();

        assertThat(calls).containsExactlyInAnyOrder("RF 2026-10-09T13:10", "KZ 2026-10-09T14:10");
        assertThat(MarketContext.get()).as("рынок потока снят после прохода").isEqualTo(Market.RF);
    }
}
