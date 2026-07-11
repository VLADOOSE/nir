package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Асинхронный запуск импорта СК-Фармации с живым прогрессом (зеркалит GoszakupImportScheduler). */
@Component
public class SkPharmacyImportScheduler {

    public record ImportStatus(boolean running, Instant lastFinishedAt, ImportSummary lastSummary) {}

    private final SkPharmacyImportService importService;
    private final boolean enabled;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastFinishedAt;
    private volatile ImportSummary lastSummary;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sk-import");
        t.setDaemon(true);
        return t;
    });

    public SkPharmacyImportScheduler(SkPharmacyImportService importService,
                                     @Value("${skpharmacy.import.enabled:false}") boolean enabled) {
        this.importService = importService;
        this.enabled = enabled;
    }

    public ImportStatus status() {
        return new ImportStatus(running.get(), lastFinishedAt, lastSummary);
    }

    @Scheduled(fixedDelayString = "${skpharmacy.import.poll-ms:21600000}")
    public void tick() {
        if (enabled) startAsync();
    }

    /** Стартует импорт в фоне, сразу возвращает статус; lastSummary наполняется по ходу. */
    public ImportStatus startAsync() {
        if (!running.compareAndSet(false, true)) {
            return status();   // уже идёт — текущий прогресс
        }
        ImportSummary sum = new ImportSummary();
        lastSummary = sum;
        executor.submit(() -> {
            MarketContext.set(Market.KZ);   // §6: рынок в ФОНОВОМ потоке
            try {
                importService.fillImport(sum);
            } catch (Exception e) {
                sum.setMessage("Ошибка импорта СК-Фармации: " + e.getMessage());
            } finally {
                lastFinishedAt = Instant.now();
                running.set(false);
                MarketContext.clear();
            }
        });
        return status();
    }
}
