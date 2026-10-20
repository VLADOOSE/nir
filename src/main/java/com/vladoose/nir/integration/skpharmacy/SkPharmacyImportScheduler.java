package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.productwatch.ProductImportMarks;
import com.vladoose.nir.service.productwatch.ProductWatchNotifier;
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
 * Асинхронный запуск импорта СК-Фармации с живым прогрессом (зеркалит GoszakupImportScheduler): кнопка и автозапуск
 * ({@code TenderAutoImportScheduler}) ставят прогон в свой поток; в конце — уведомление о новых тендерах.
 */
@Component
public class SkPharmacyImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(SkPharmacyImportScheduler.class);

    public record ImportStatus(boolean running, Instant lastFinishedAt, ImportSummary lastSummary) {}

    private final SkPharmacyImportService importService;
    private final NewTenderNotifier notifier;
    private final ImportOutageNotifier outage;
    /** Отметки «наш товар» у созданных и обновлённых тендеров (спека 2026-10-19-product-watch §8). */
    private final Consumer<ImportSummary> productMarks;
    /** «Наш товар в тендерах» — о затронутых прогоном, кроме уже сообщённых в «🏥 Новый тендер» (спека §9). */
    private final Consumer<ImportSummary> productNotify;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastFinishedAt;
    private volatile ImportSummary lastSummary;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sk-import");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public SkPharmacyImportScheduler(SkPharmacyImportService importService, NewTenderNotifier notifier,
                                     ImportOutageNotifier outage, ProductImportMarks marks, ProductWatchNotifier productNotifier) {
        this(importService, notifier, outage, marks::afterImport, productNotifier::afterRun);
    }

    /** Без нашей продукции — тесты планировщика. */
    public SkPharmacyImportScheduler(SkPharmacyImportService importService, NewTenderNotifier notifier,
                                     ImportOutageNotifier outage) {
        this(importService, notifier, outage, sum -> { }, sum -> { });
    }

    SkPharmacyImportScheduler(SkPharmacyImportService importService, NewTenderNotifier notifier, ImportOutageNotifier outage,
                              Consumer<ImportSummary> productMarks, Consumer<ImportSummary> productNotify) {
        this.importService = importService;
        this.notifier = notifier;
        this.outage = outage;
        this.productMarks = productMarks;
        this.productNotify = productNotify;
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
                markProducts(sum);       // отметки «наш товар» — до уведомления (в нём строка «наш товар»)
                notifyNewTenders(sum);   // и после сбоя: созданное до него — тоже новое
                notifyProducts(sum);     // наш товар в остальных затронутых тендерах (новые — уже в «🏥 Новый тендер»)
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
            log.warn("СК-Фармация: отметки «наш товар» упали: {}", ErrorText.of(e));
        }
    }

    /** Ещё в потоке прогона и под рынком KZ (§6); сбой уведомления итог прогона не роняет. */
    private void notifyNewTenders(ImportSummary sum) {
        try {
            notifier.afterImport(sum);
        } catch (Throwable e) {
            log.warn("СК-Фармация: уведомление о новых тендерах упало: {}", ErrorText.of(e));
        }
    }

    /** «Наш товар в тендерах» — в потоке прогона и под рынком KZ; сбой итог прогона не роняет. */
    private void notifyProducts(ImportSummary sum) {
        try {
            productNotify.accept(sum);
        } catch (Throwable e) {
            log.warn("СК-Фармация: уведомление «наш товар» упало: {}", ErrorText.of(e));
        }
    }

    /** Оповещение о сбое площадки; само ничего не бросает, но и его падение итог прогона не роняет. */
    private void reportOutage(ImportSummary sum) {
        try {
            outage.afterRun("СК-Фармации", sum);   // в родительном: «Импорт СК-Фармации не работает»
        } catch (Throwable e) {
            log.warn("СК-Фармация: оповещение о сбое импорта упало: {}", ErrorText.of(e));
        }
    }
}
