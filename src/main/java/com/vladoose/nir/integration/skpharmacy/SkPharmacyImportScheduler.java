package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.tendernotify.NewTenderNotifier;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Асинхронный запуск импорта СК-Фармации с живым прогрессом (зеркалит GoszakupImportScheduler): кнопка и автозапуск
 * ({@code TenderAutoImportScheduler}) ставят прогон в свой поток; в конце — уведомление о новых тендерах.
 */
@Component
public class SkPharmacyImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(SkPharmacyImportScheduler.class);

    public record ImportStatus(boolean running, Instant lastFinishedAt, ImportSummary lastSummary) {}

    private final SkPharmacyImportService importService;
    private final NewTenderNotifier notifier;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastFinishedAt;
    private volatile ImportSummary lastSummary;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sk-import");
        t.setDaemon(true);
        return t;
    });

    public SkPharmacyImportScheduler(SkPharmacyImportService importService, NewTenderNotifier notifier) {
        this.importService = importService;
        this.notifier = notifier;
    }

    public ImportStatus status() {
        return new ImportStatus(running.get(), lastFinishedAt, lastSummary);
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
            } catch (Throwable e) {   // и Error: иначе он тонул в Future, а прогон выглядел бы «без ошибок»
                log.error("СК-Фармация: импорт прерван", e);
                sum.addError("прогон прерван: " + ErrorText.of(e));
                sum.setMessage("Ошибка импорта СК-Фармации: " + ErrorText.of(e));
            } finally {
                notifyNewTenders(sum);   // и после сбоя: созданное до него — тоже новое
                lastFinishedAt = Instant.now();
                running.set(false);
                MarketContext.clear();
            }
        });
        return status();
    }

    /** Ещё в потоке прогона и под рынком KZ (§6); сбой уведомления итог прогона не роняет. */
    private void notifyNewTenders(ImportSummary sum) {
        try {
            notifier.afterImport(sum);
        } catch (Throwable e) {
            log.warn("СК-Фармация: уведомление о новых тендерах упало: {}", ErrorText.of(e));
        }
    }
}
