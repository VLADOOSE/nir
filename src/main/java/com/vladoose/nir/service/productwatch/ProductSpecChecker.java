package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductMatchSource;
import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TenderLotProductMatch;
import com.vladoose.nir.integration.goszakup.GoszakupClient;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.repository.TenderLotProductMatchRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.LotSpecParser;
import com.vladoose.nir.service.TechSpecLotParser;
import com.vladoose.nir.service.TechSpecStatusWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * ТЗ найденных лотов (спека 2026-10-19-product-watch §7.4): не больше limit за прогон, сначала ближайший срок.
 * Попытка отмечается ДО разбора (урок фоновой очереди: SIGKILL не ловится никаким catch); разобрано — пересчёт отметок
 * тендера по ТЗ; нет файла / не читается / 3 неудачи — сверка закончена, отметка остаётся по названию. Три аварии
 * площадки подряд — пачка обрывается (как у фоновой очереди). ТЗ — только у тендеров, где сумма всех отмеченных лотов
 * не меньше порога {@code PRODUCT_WATCH_NOTIFY_MIN_SUM} (спека 2026-10-20-product-spec-fit §12): у дешёвых сверка
 * в Telegram всё равно не уйдёт, а скачивание тратит общий с продом токен goszakup.
 */
@Component
public class ProductSpecChecker {

    private static final Logger log = LoggerFactory.getLogger(ProductSpecChecker.class);
    static final int MAX_ATTEMPTS = 3;
    static final int OUTAGE_STOP = 3;
    static final long PAUSE_MS = 300;

    private final TenderLotProductMatchRepository matches;
    private final TenderLotRepository lots;
    private final TechSpecStatusWriter statusWriter;
    private final LotSpecParser parser;
    private final ProductMatchService matchService;
    private final BooleanSupplier goszakupConfigured;
    private final UpstreamRetry.Sleeper sleeper;
    private final ProductNotifyPolicy policy;

    @Autowired
    public ProductSpecChecker(TenderLotProductMatchRepository matches, TenderLotRepository lots, TechSpecStatusWriter statusWriter,
                              TechSpecLotParser parser, ProductMatchService matchService, GoszakupClient goszakup,
                              @Value("${product-watch.notify:}") String notify, @Value("${product-watch.notify-min-sum:}") String notifyMinSum) {
        this(matches, lots, statusWriter, parser, matchService, goszakup::isConfigured, UpstreamRetry.REAL,
                ProductNotifyPolicy.of(notify, notifyMinSum));
    }

    public ProductSpecChecker(TenderLotProductMatchRepository matches, TenderLotRepository lots, TechSpecStatusWriter statusWriter,
                              LotSpecParser parser, ProductMatchService matchService, BooleanSupplier goszakupConfigured,
                              UpstreamRetry.Sleeper sleeper, ProductNotifyPolicy policy) {
        this.matches = matches; this.lots = lots; this.statusWriter = statusWriter; this.parser = parser;
        this.matchService = matchService; this.goszakupConfigured = goszakupConfigured; this.sleeper = sleeper; this.policy = policy;
    }

    public int run(int limit, ImportSummary sum) {
        List<TenderLot> candidates = lots.findAllById(matches.findLotsForSpecCheckUnordered(goszakupConfigured.getAsBoolean()));
        if (candidates.isEmpty()) return 0;
        // отметки кандидатов — одним запросом; порог — до предела пачки: дешёвый лот не занимает место дорогого
        Map<Long, List<Long>> marked = matches.findByTenderIdIn(candidates.stream().map(l -> l.getTender().getId()).collect(Collectors.toSet()))
                .stream().collect(Collectors.groupingBy(TenderLotProductMatch::getTenderId,
                        Collectors.mapping(TenderLotProductMatch::getLotId, Collectors.toList())));
        List<TenderLot> queue = candidates.stream()
                .filter(l -> policy.sumPasses(OurLots.sum(l.getTender(), marked.getOrDefault(l.getTender().getId(), List.of()))))
                .sorted(Comparator.comparing((TenderLot l) -> l.getTender().getDeadlineAt(), Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder())))
                .limit(limit).toList();
        int done = 0, outages = 0;
        for (TenderLot lot : queue) {
            long lotId = lot.getId(), tenderId = lot.getTender().getId();
            if (lot.getTechSpecStatus() == TechSpecStatus.OK) {      // ТЗ уже разобрано кнопкой или очередью — без скачивания
                matchService.evaluate(tenderId, ProductMatchSource.SEARCH);
                done++;
                continue;
            }
            pause();
            statusWriter.incrementAttempts(lotId);
            TechSpecLotParser.Result r = parser.parse(lotId);
            if (r.status() == TechSpecStatus.OK) {
                matchService.evaluate(tenderId, ProductMatchSource.SEARCH);
                done++;
            } else if (r.status() == TechSpecStatus.NO_FILE || r.status() == TechSpecStatus.UNREADABLE
                    || lots.findById(lotId).map(TenderLot::getTechSpecAttempts).orElse(MAX_ATTEMPTS) >= MAX_ATTEMPTS) {
                matchService.markSpecChecked(lotId);
                done++;
            }
            outages = r.outage() ? outages + 1 : 0;
            if (outages >= OUTAGE_STOP) {
                sum.addSideError("ТЗ: площадка недоступна — " + OUTAGE_STOP + " лота подряд, разбор отложен");
                log.warn("Поиск нашей продукции: ТЗ — {} отказа площадки подряд, пачка оборвана", OUTAGE_STOP);
                break;
            }
        }
        return done;
    }

    private void pause() {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
