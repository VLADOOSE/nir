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

import java.util.List;

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

    private final TenderLotRepository lotRepository;
    private final TechSpecService techSpecService;
    private final TechSpecStatusWriter writer;
    private final GoszakupClient goszakupClient;

    @Value("${techspec.backfill.enabled:false}")    private boolean enabled;
    @Value("${techspec.backfill.batch-size:10}")    private int batchSize;
    @Value("${techspec.backfill.throttle-ms:2000}") private long throttleMs;

    /** Про незаданный токен говорим один раз, а не каждые 10 минут. */
    private boolean noTokenLogged;

    public TechSpecBackfillScheduler(TenderLotRepository lotRepository,
                                     TechSpecService techSpecService,
                                     TechSpecStatusWriter writer,
                                     GoszakupClient goszakupClient) {
        this.lotRepository = lotRepository;
        this.techSpecService = techSpecService;
        this.writer = writer;
        this.goszakupClient = goszakupClient;
    }

    /** Каждые 10 минут: небольшая пачка, чтобы не долбить площадку. */
    @Scheduled(fixedDelayString = "${techspec.backfill.interval-ms:600000}")
    public void tick() {
        if (enabled) {
            runOnce();
        }
    }

    /**
     * Одна пачка очереди. Публичный и БЕЗ проверки флага (как {@code MailPollScheduler.run()}):
     * тестам нужен воркер, а не расписание — включать флаг в тестовом контексте нельзя, фоновый
     * поток пошёл бы разбирать реальную очередь nirdb вне транзакции теста.
     */
    public void runOnce() {
        try {
            MarketContext.set(Market.KZ);   // §6: фоновый поток, рынок ставим ЯВНО
            List<Long> lotIds = nextBatch();
            if (lotIds.isEmpty()) return;
            log.info("Фоновый разбор ТЗ: взято {} лотов", lotIds.size());
            for (Long lotId : lotIds) {
                if (Thread.currentThread().isInterrupted()) break;   // остановка приложения
                processOne(lotId);
                throttle();
            }
        } finally {
            MarketContext.clear();
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

    /** Один лот. Исход записывается ВСЕГДА — иначе очередь качала бы один и тот же PDF вечно. */
    private void processOne(Long lotId) {
        TechSpecStatus status;
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
        } catch (RuntimeException e) {
            log.warn("Разбор ТЗ лота {} упал неожиданно: {}", lotId, e.toString());
            status = TechSpecStatus.ERROR;
        }
        writer.markResult(lotId, status);      // отдельный бин → есть привязанная сессия
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
