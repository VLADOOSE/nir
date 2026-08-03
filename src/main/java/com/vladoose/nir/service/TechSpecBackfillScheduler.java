package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.entity.TenderPlatform;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.exception.UnprocessableException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.goszakup.GoszakupClient;
import com.vladoose.nir.repository.TenderLotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Фоновый дозаполнитель техспек. Сам разбор реализован в {@link TechSpecService} — здесь только
 * очередь вокруг него: без неё ТЗ разобрано у 11 лотов из 1344, у половины лотов имя состоит из
 * одного слова, и матчеру нечем различать записи реестра.
 *
 * <p>Импортные каналы (goszakup, СК-Фармация) существуют только на рынке KZ, поэтому очередь
 * ходит по KZ; рынок ставится ЯВНО — у фонового потока нет ни HTTP-запроса, ни привязанной
 * сессии, и рыночный аспект сам не сработает (§6). Сеть и PDF — вне транзакции; исход пишет
 * отдельный бин {@link TechSpecStatusWriter}.
 *
 * <p>⚠ На проде goszakup блокирует IP (PROGRESS §4) → его лоты осядут в ERROR. Это корректное
 * поведение: UI честно покажет «ТЗ получить не удалось», а не «подбор не работает».
 * СК-Фармация токена не требует и от блокировки не страдает.
 */
@Service
public class TechSpecBackfillScheduler {

    private static final Logger log = LoggerFactory.getLogger(TechSpecBackfillScheduler.class);

    /** Столько отказов площадки подряд — и пачка обрывается (предохранитель). */
    private static final int OUTAGES_TO_TRIP = 3;

    private final TenderLotRepository lotRepository;
    private final TechSpecService techSpecService;
    private final TechSpecStatusWriter writer;
    private final GoszakupClient goszakupClient;

    @Value("${techspec.backfill.enabled:false}")       private boolean enabled;
    @Value("${techspec.backfill.batch-size:10}")       private int batchSize;
    @Value("${techspec.backfill.throttle-ms:2000}")    private long throttleMs;
    @Value("${techspec.backfill.retry-after-days:7}")  private int retryAfterDays;

    /** Про незаданный токен говорим один раз, а не каждые 10 минут. */
    private boolean noTokenLogged;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "techspec-backfill");
        t.setDaemon(true);
        return t;
    });

    public TechSpecBackfillScheduler(TenderLotRepository lotRepository,
                                     TechSpecService techSpecService,
                                     TechSpecStatusWriter writer,
                                     GoszakupClient goszakupClient) {
        this.lotRepository = lotRepository;
        this.techSpecService = techSpecService;
        this.writer = writer;
        this.goszakupClient = goszakupClient;
    }

    /**
     * Каждые 10 минут: небольшая пачка, чтобы не долбить площадку. Работа уходит на СВОЙ
     * однопоточный экзекьютор (как у {@code GoszakupImportScheduler}), а не на общий
     * {@code scheduling-1}: при DROP-блокировке пачка из 10 лотов — это 10 × (15 с connect ×
     * 3 ретрая {@code GoszakupRetry}) ≈ 470 с при интервале 600 с, и приём почты (300 с)
     * простаивал бы половину времени.
     */
    @Scheduled(fixedDelayString = "${techspec.backfill.interval-ms:600000}")
    public void tick() {
        if (!enabled) return;
        if (!running.compareAndSet(false, true)) {
            log.debug("Фоновый разбор ТЗ: предыдущая пачка ещё идёт — пропускаем тик");
            return;
        }
        // execute, а не submit: непойманный Error должен дойти до обработчика потока, а не
        // осесть в Future, которую никто не читает (см. processOne — Error пробрасывается).
        executor.execute(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                log.warn("Фоновый разбор ТЗ: пачка прервана: {}", e.toString());
            } finally {
                running.set(false);
            }
        });
    }

    /**
     * Одна пачка очереди. Публичный и БЕЗ проверки флага (как {@code MailPollScheduler.run()}):
     * тестам нужен воркер, а не расписание — включать флаг в тестовом контексте нельзя, фоновый
     * поток пошёл бы разбирать реальную очередь nirdb вне транзакции теста.
     */
    public void runOnce() {
        try {
            MarketContext.set(Market.KZ);   // §6: фоновый поток, рынок ставим ЯВНО
            requeueStaleErrors();
            List<Long> lotIds = nextBatch();
            if (lotIds.isEmpty()) return;
            log.info("Фоновый разбор ТЗ: взято {} лотов", lotIds.size());
            int outagesInARow = 0;
            for (Long lotId : lotIds) {
                if (Thread.currentThread().isInterrupted()) break;   // остановка приложения
                if (processOne(lotId)) {
                    // Предохранитель: площадка лежит — лоты не виноваты. Без обрыва пачки одна
                    // внешняя авария (IP-блок прода) списала бы в ERROR всю очередь за сутки.
                    if (++outagesInARow >= OUTAGES_TO_TRIP) {
                        log.warn("Фоновый разбор ТЗ: {} отказа площадки подряд — пачка прервана,"
                               + " остальные лоты остаются в очереди нетронутыми", outagesInARow);
                        break;
                    }
                } else {
                    outagesInARow = 0;
                }
                throttle();
            }
        } finally {
            MarketContext.clear();
        }
    }

    /** Сгоревшие на аварии лоты возвращаются в очередь — иначе ERROR был бы приговором. */
    private void requeueStaleErrors() {
        if (retryAfterDays <= 0) return;   // 0 — выключить возврат
        int n = writer.requeueStaleErrors(OffsetDateTime.now().minusDays(retryAfterDays));
        if (n > 0) {
            log.info("Фоновый разбор ТЗ: {} лотов вернулись в очередь (ошибка старше {} дней)",
                    n, retryAfterDays);
        }
    }

    /**
     * Пачка из очереди. Без токена goszakup берём ТОЛЬКО СК-Фармацию: goszakup-лот в этом случае
     * нельзя ни разобрать, ни честно списать — «нет токена» это состояние конфигурации, а не
     * свойство лота, и списание в NO_FILE навсегда похоронило бы 97 % очереди из-за пустого env.
     */
    private List<Long> nextBatch() {
        var page = PageRequest.of(0, batchSize);
        if (goszakupClient.isConfigured()) {
            return lotRepository.findPendingTechSpec(Market.KZ, page);
        }
        if (!noTokenLogged) {
            log.info("Фоновый разбор ТЗ: токен goszakup не задан — очередь идёт только по СК-Фармации");
            noTokenLogged = true;
        }
        return lotRepository.findPendingTechSpecByPlatform(Market.KZ, TenderPlatform.SK_PHARMACY, page);
    }

    /**
     * Один лот. Исход записывается ВСЕГДА — иначе очередь качала бы один и тот же PDF вечно.
     *
     * @return true, если лот не разобрался ИМЕННО из-за недоступности площадки (для предохранителя)
     */
    private boolean processOne(Long lotId) {
        TechSpecStatus status;
        boolean outage = false;
        Error fatal = null;
        try {
            techSpecService.parse(lotId);      // сеть + PDF, ВНЕ транзакции
            status = TechSpecStatus.OK;
        } catch (NotFoundException e) {
            status = TechSpecStatus.NO_FILE;
        } catch (UnprocessableException e) {
            status = TechSpecStatus.UNREADABLE;
        } catch (BadRequestException e) {
            // Лот принципиально неразбираем: SK-лот без кода площадки, тендер без sourceExtId.
            // Случай «нет токена» сюда не доходит — такие лоты в пачку не попадают (nextBatch).
            status = TechSpecStatus.NO_FILE;
        } catch (UpstreamException e) {
            status = TechSpecStatus.ERROR;
            outage = true;
        } catch (RuntimeException e) {
            log.warn("Разбор ТЗ лота {} упал неожиданно: {}", lotId, e.toString());
            status = TechSpecStatus.ERROR;
        } catch (Error e) {
            // ⚠ Не «на всякий случай»: OutOfMemoryError на этом самом пути (PDFBox грузит PDF в
            // память) — наблюдавшееся состояние, §5/§16, прод живёт на -Xmx1g. Без этой ветки
            // Error пролетал бы мимо markResult, лот остался бы PENDING, и следующий тик выбрал
            // бы ТОТ ЖЕ лот (запрос детерминированный) — очередь встала бы навсегда на одном PDF.
            log.error("Разбор ТЗ лота {} упал фатально: {}", lotId, e.toString());
            status = TechSpecStatus.ERROR;
            fatal = e;
        }
        writer.markResult(lotId, status);      // отдельный бин → есть привязанная сессия
        // Исход записан — теперь можно отдать Error наружу: глушить обработку JVM мы не вправе.
        if (fatal != null) throw fatal;
        return outage;
    }

    private void throttle() {
        if (throttleMs <= 0) return;
        try {
            Thread.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
