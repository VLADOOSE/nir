package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GoszakupImportScheduler {

    private final GoszakupImportService importService;
    private final boolean enabled;

    public GoszakupImportScheduler(GoszakupImportService importService,
                                   @Value("${goszakup.import.enabled:false}") boolean enabled) {
        this.importService = importService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${goszakup.import.poll-ms:21600000}")
    public void tick() {
        if (enabled) run();
    }

    /** §6: ставит рынок KZ вокруг @Transactional-сервиса (отдельный бин → аспект/прокси работают), чистит. */
    public ImportSummary run() {
        return run(null);
    }

    /** region — каноническое имя региона для серверного КАТО-фильтра, null = вся лента. */
    public ImportSummary run(String region) {
        MarketContext.set(Market.KZ);
        try {
            return importService.importMedicalTenders(region);
        } finally {
            MarketContext.clear();
        }
    }
}
