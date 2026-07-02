package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class GoszakupImportScheduler {

    /** Снимок для UI: идёт ли импорт и чем закончился последний прогон. */
    public record ImportStatus(boolean running, Instant lastFinishedAt, String lastRegion, ImportSummary lastSummary) {}

    private final GoszakupImportService importService;
    private final boolean enabled;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastFinishedAt;
    private volatile String lastRegion;
    private volatile ImportSummary lastSummary;

    public GoszakupImportScheduler(GoszakupImportService importService,
                                   @Value("${goszakup.import.enabled:false}") boolean enabled) {
        this.importService = importService;
        this.enabled = enabled;
    }

    public ImportStatus status() {
        return new ImportStatus(running.get(), lastFinishedAt, lastRegion, lastSummary);
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
        if (!running.compareAndSet(false, true)) {
            ImportSummary busy = new ImportSummary();
            busy.setEnabled(false);
            busy.setMessage("Импорт уже выполняется — дождитесь окончания");
            return busy;
        }
        MarketContext.set(Market.KZ);
        try {
            ImportSummary sum = importService.importMedicalTenders(region);
            lastRegion = region;
            lastSummary = sum;
            return sum;
        } finally {
            lastFinishedAt = Instant.now();
            running.set(false);
            MarketContext.clear();
        }
    }
}
