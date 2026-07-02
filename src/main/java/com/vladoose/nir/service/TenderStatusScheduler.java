package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Ежедневно (и на старте) закрывает просроченные ACTIVE-тендеры в обоих рынках (§6). */
@Component
public class TenderStatusScheduler {

    private static final Logger log = LoggerFactory.getLogger(TenderStatusScheduler.class);

    private final TenderStatusService statusService;

    public TenderStatusScheduler(TenderStatusService statusService) {
        this.statusService = statusService;
    }

    @Scheduled(cron = "0 5 0 * * *")
    @EventListener(ApplicationReadyEvent.class)
    public void completeExpiredForAllMarkets() {
        for (Market m : Market.values()) {
            MarketContext.set(m);
            try {
                int n = statusService.completeExpired(LocalDate.now());
                if (n > 0) log.info("тендеры: {} просроченных ACTIVE → COMPLETED (рынок {})", n, m);
            } finally {
                MarketContext.clear();
            }
        }
    }
}
