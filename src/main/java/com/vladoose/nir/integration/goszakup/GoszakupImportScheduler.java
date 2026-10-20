package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.service.productwatch.ProductImportMarks;
import com.vladoose.nir.service.tendernotify.ImportOutageNotifier;
import com.vladoose.nir.service.tendernotify.NewTenderNotifier;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private final ImportOutageNotifier outage;
    /** Отметки «наш товар» у созданных и обновлённых тендеров (спека 2026-10-19-product-watch §8). */
    private final Consumer<ImportSummary> productMarks;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastFinishedAt;
    private volatile String lastRegion;
    private volatile ImportSummary lastSummary;
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "goszakup-import");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public GoszakupImportScheduler(GoszakupImportService importService, NewTenderNotifier notifier,
                                   ImportOutageNotifier outage, ProductImportMarks marks) {
        this.importService = importService;
        this.notifier = notifier;
        this.outage = outage;
        this.productMarks = marks::afterImport;
    }

    /** Без отметок «наш товар» — тесты планировщика. */
    public GoszakupImportScheduler(GoszakupImportService importService, NewTenderNotifier notifier,
                                   ImportOutageNotifier outage) {
        this.importService = importService;
        this.notifier = notifier;
        this.outage = outage;
        this.productMarks = sum -> { };
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
                markProducts(sum);       // отметки «наш товар» — до уведомления (в нём строка «наш товар»)
                notifyNewTenders(sum);   // и после сбоя: созданное до него — тоже новое
                reportOutage(sum);       // авария площадки (упавшие подряд прогоны без записей) — в Telegram
                lastFinishedAt = Instant.now();
                running.set(false);
                MarketContext.clear();
            }
        });
        return status();
    }

    /** В потоке прогона и под рынком KZ (§6); сбой отметок итог прогона не роняет. */
    private void markProducts(ImportSummary sum) {
        try {
            productMarks.accept(sum);
        } catch (Throwable e) {
            log.warn("goszakup: отметки «наш товар» упали: {}", ErrorText.of(e));
        }
    }

    /** Ещё в потоке прогона и под рынком KZ (§6); сбой уведомления итог прогона не роняет. */
    private void notifyNewTenders(ImportSummary sum) {
        try {
            notifier.afterImport(sum);
        } catch (Throwable e) {
            log.warn("goszakup: уведомление о новых тендерах упало: {}", ErrorText.of(e));
        }
    }

    /** Оповещение о сбое площадки; само ничего не бросает, но и его падение итог прогона не роняет. */
    private void reportOutage(ImportSummary sum) {
        try {
            outage.afterRun("goszakup", sum);
        } catch (Throwable e) {
            log.warn("goszakup: оповещение о сбое импорта упало: {}", ErrorText.of(e));
        }
    }
}
