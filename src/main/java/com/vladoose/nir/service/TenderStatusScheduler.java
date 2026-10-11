package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Каждый час (и на старте) закрывает просроченные ACTIVE-тендеры в обоих рынках (§6). Раз в час, а не в полночь:
 * срок площадки — с временем (B2). «Сейчас» — по часам рынка: прод живёт в UTC, а срок «до 10:25» — местное время.
 */
@Component
public class TenderStatusScheduler {

    private static final Logger log = LoggerFactory.getLogger(TenderStatusScheduler.class);

    private final TenderStatusService statusService;
    private final Clock clock;

    @Autowired
    public TenderStatusScheduler(TenderStatusService statusService) {
        this(statusService, Clock.systemUTC());
    }

    TenderStatusScheduler(TenderStatusService statusService, Clock clock) {
        this.statusService = statusService;
        this.clock = clock;
    }

    @Scheduled(cron = "0 5 * * * *")
    @EventListener(ApplicationReadyEvent.class)
    public void completeExpiredForAllMarkets() {
        for (Market m : Market.values()) {
            MarketContext.set(m);
            try {
                int n = statusService.completeExpired(LocalDateTime.ofInstant(clock.instant(), m.zone()));
                if (n > 0) log.info("тендеры: {} просроченных ACTIVE → COMPLETED (рынок {})", n, m);
            } finally {
                MarketContext.clear();
            }
        }
    }
}
