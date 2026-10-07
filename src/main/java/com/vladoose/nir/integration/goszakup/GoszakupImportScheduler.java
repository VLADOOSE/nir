package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
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
 * Фоновый прогон импорта goszakup с живым прогрессом: свой однопоточный исполнитель, кнопка и автозапуск
 * ({@code TenderAutoImportScheduler}) только ставят прогон в него. В конце прогона — уведомление о новых тендерах.
 */
@Component
public class GoszakupImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(GoszakupImportScheduler.class);

    /** Снимок для UI: идёт ли импорт и чем закончился последний прогон. */
    public record ImportStatus(boolean running, Instant lastFinishedAt, String lastRegion, ImportSummary lastSummary) {}

    private final GoszakupImportService importService;
    private final NewTenderNotifier notifier;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastFinishedAt;
    private volatile String lastRegion;
    private volatile ImportSummary lastSummary;
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "goszakup-import");
        t.setDaemon(true);
        return t;
    });

    public GoszakupImportScheduler(GoszakupImportService importService, NewTenderNotifier notifier) {
        this.importService = importService;
        this.notifier = notifier;
    }

    public ImportStatus status() {
        return new ImportStatus(running.get(), lastFinishedAt, lastRegion, lastSummary);
    }

    /** Стартует импорт в фоне и сразу возвращает статус; lastSummary наполняется по ходу (живой прогресс). */
    public ImportStatus startAsync(String region) {
        if (!running.compareAndSet(false, true)) {
            return status(); // уже идёт — вернём текущий прогресс
        }
        ImportSummary sum = new ImportSummary();
        lastRegion = region;
        lastSummary = sum;
        importExecutor.submit(() -> {
            // §6: рынок ставится В ФОНОВОМ потоке (ThreadLocal), не в HTTP-потоке
            MarketContext.set(Market.KZ);
            try {
                importService.fillImport(region, sum);
            } catch (Throwable e) {
                // без этого исключение (и Error) тонуло в Future: прогон кончался без итога, и тоста не было вовсе
                log.error("goszakup: импорт прерван", e);
                sum.addError("прогон прерван: " + ErrorText.of(e));
                sum.setMessage("Импорт прерван: " + ErrorText.of(e));
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
            log.warn("goszakup: уведомление о новых тендерах упало: {}", ErrorText.of(e));
        }
    }
}
