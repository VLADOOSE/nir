package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TenderPlatform;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.exception.UnprocessableException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.goszakup.GoszakupClient;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Очередь фонового авторазбора ТЗ: выборка PENDING, запись исхода, таксономия ошибок.
 *
 * <p>Разбор ({@link TechSpecService}) замокан — сети в тестах нет; проверяется именно очередь:
 * что лот берётся, что ЛЮБОЙ исход его из очереди убирает, и что без токена goszakup очередь
 * не трогает его лоты. {@link GoszakupClient} тоже мок — иначе тест зависел бы от того, задан
 * ли GOSZAKUP_TOKEN в окружении разработчика.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "techspec.backfill.enabled=false",  // расписание не должно трогать реальную очередь nirdb
        "techspec.backfill.batch-size=1",   // берём ровно свежесозданный лот (ORDER BY id DESC)
        "techspec.backfill.throttle-ms=0"   // без пауз
})
class TechSpecBackfillSchedulerTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired TechSpecStatusWriter writer;
    @Autowired TechSpecBackfillScheduler scheduler;
    @PersistenceContext EntityManager em;

    @MockitoBean TechSpecService techSpecService;
    @MockitoBean GoszakupClient goszakupClient;

    @BeforeEach void setUp() {
        MarketContext.set(Market.KZ);
        when(goszakupClient.isConfigured()).thenReturn(true);
    }

    @AfterEach void tearDown() { MarketContext.clear(); }

    private Tender tenderWithLot(TechSpecStatus status) {
        return tenderWithLot(status, TenderPlatform.SK_PHARMACY);
    }

    /** Импортный тендер (sourceExtId + площадка) с одним лотом в заданном статусе очереди. */
    private Tender tenderWithLot(TechSpecStatus status, TenderPlatform platform) {
        Tender t = new Tender();
        t.setTenderNumber("BACKFILL-" + System.nanoTime());
        t.setSourceExtId(t.getTenderNumber());
        t.setPlatform(platform);
        t.setStatus("ACTIVE");
        t.setSource(Source.PUBLIC_TENDER);
        t.setMarket(Market.KZ);
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setEquipName("Центрифуга");
        l.setSourceLotCode("BF-Т1");
        l.setTechSpecStatus(status);
        t.getLots().add(l);
        return tenderRepository.save(t);
    }

    private static Long lotId(Tender t) { return t.getLots().get(0).getId(); }

    /** Прочитать лот из БД, а не из persistence context: иначе ассерт увидел бы объект в памяти. */
    private TenderLot reload(Long id) {
        em.flush();
        em.clear();
        return lotRepository.findById(id).orElseThrow();
    }

    /** runOnce() чистит MarketContext в finally — без восстановления ассерты ушли бы на рынок РФ. */
    private void runQueue() {
        scheduler.runOnce();
        MarketContext.set(Market.KZ);
    }

    // ---------- очередь ----------

    @Test
    void pendingQueryReturnsEnqueuedLot() {
        Tender t = tenderWithLot(TechSpecStatus.PENDING);
        lotRepository.flush();

        assertThat(lotRepository.findPendingTechSpec(Market.KZ, PageRequest.of(0, 50)))
                .contains(lotId(t));
    }

    /** Уже обработанный лот очередь больше не берёт — иначе крутила бы его вечно. */
    @Test
    void pendingQuerySkipsProcessedLot() {
        Tender t = tenderWithLot(TechSpecStatus.OK);
        lotRepository.flush();

        assertThat(lotRepository.findPendingTechSpec(Market.KZ, PageRequest.of(0, 50)))
                .doesNotContain(lotId(t));
    }

    @Test
    void pendingQueryRespectsBatchSize() {
        tenderWithLot(TechSpecStatus.PENDING);
        tenderWithLot(TechSpecStatus.PENDING);
        tenderWithLot(TechSpecStatus.PENDING);
        lotRepository.flush();

        assertThat(lotRepository.findPendingTechSpec(Market.KZ, PageRequest.of(0, 2))).hasSize(2);
    }

    // ---------- запись исхода ----------

    @Test
    void markResultRecordsStatusAndAttemptTime() {
        Tender t = tenderWithLot(TechSpecStatus.PENDING);

        writer.markResult(lotId(t), TechSpecStatus.ERROR);

        TenderLot reloaded = reload(lotId(t));
        assertThat(reloaded.getTechSpecStatus()).isEqualTo(TechSpecStatus.ERROR);
        assertThat(reloaded.getTechSpecAttemptedAt()).isNotNull();
    }

    /** После записи исхода лот выпадает из очереди — гарантия от бесконечного перекачивания PDF. */
    @Test
    void markResultRemovesLotFromQueue() {
        Tender t = tenderWithLot(TechSpecStatus.PENDING);
        Long id = lotId(t);

        writer.markResult(id, TechSpecStatus.NO_FILE);
        lotRepository.flush();

        assertThat(lotRepository.findPendingTechSpec(Market.KZ, PageRequest.of(0, 50)))
                .doesNotContain(id);
    }

    // ---------- воркер: таксономия исходов ----------

    @Test
    void successfulParseLeavesLotOk() {
        Tender t = tenderWithLot(TechSpecStatus.PENDING);
        lotRepository.flush();

        runQueue();

        verify(techSpecService).parse(lotId(t));
        assertThat(reload(lotId(t)).getTechSpecStatus()).isEqualTo(TechSpecStatus.OK);
    }

    @Test
    void missingFileMarksNoFile() {
        assertMapping(new NotFoundException("нет файла"), TechSpecStatus.NO_FILE);
    }

    @Test
    void unreadablePdfMarksUnreadable() {
        assertMapping(new UnprocessableException("PDF не читается"), TechSpecStatus.UNREADABLE);
    }

    @Test
    void unparseableLotMarksNoFile() {
        assertMapping(new BadRequestException("нет кода лота площадки"), TechSpecStatus.NO_FILE);
    }

    @Test
    void platformOutageMarksError() {
        assertMapping(new UpstreamException("goszakup недоступен"), TechSpecStatus.ERROR);
    }

    /** Непредусмотренный сбой тоже обязан записать исход, иначе лот застрял бы в очереди навсегда. */
    @Test
    void unexpectedFailureMarksError() {
        assertMapping(new IllegalStateException("что-то пошло не так"), TechSpecStatus.ERROR);
    }

    private void assertMapping(RuntimeException thrown, TechSpecStatus expected) {
        Tender t = tenderWithLot(TechSpecStatus.PENDING);
        Long id = lotId(t);
        lotRepository.flush();
        when(techSpecService.parse(id)).thenThrow(thrown);

        runQueue();

        TenderLot reloaded = reload(id);
        assertThat(reloaded.getTechSpecStatus()).isEqualTo(expected);
        assertThat(reloaded.getTechSpecAttemptedAt()).isNotNull();
        assertThat(lotRepository.findPendingTechSpec(Market.KZ, PageRequest.of(0, 50)))
                .doesNotContain(id);   // из очереди вышел при ЛЮБОМ исходе
    }

    // ---------- воркер: без токена goszakup ----------

    /**
     * Без токена goszakup-лот брать нельзя: разобрать нечем, а списать его в NO_FILE значило бы
     * похоронить 97 % очереди из-за пустого env. Берём только СК-Фармацию — ей токен не нужен.
     */
    @Test
    void withoutTokenQueueSkipsGoszakupLots() {
        when(goszakupClient.isConfigured()).thenReturn(false);
        Tender sk = tenderWithLot(TechSpecStatus.PENDING, TenderPlatform.SK_PHARMACY);
        Tender gz = tenderWithLot(TechSpecStatus.PENDING, TenderPlatform.GOSZAKUP);  // id выше SK
        lotRepository.flush();

        runQueue();

        verify(techSpecService, never()).parse(lotId(gz));
        assertThat(reload(lotId(gz)).getTechSpecStatus()).isEqualTo(TechSpecStatus.PENDING);
        assertThat(reload(lotId(sk)).getTechSpecStatus()).isEqualTo(TechSpecStatus.OK);
    }

    /** С токеном ограничение снимается — берётся самый свежий лот любой площадки. */
    @Test
    void withTokenQueueTakesGoszakupLots() {
        Tender gz = tenderWithLot(TechSpecStatus.PENDING, TenderPlatform.GOSZAKUP);
        lotRepository.flush();

        runQueue();

        verify(techSpecService).parse(lotId(gz));
    }

    /** Выключенный флаг — расписание не трогает очередь (дефолт: enabled=false). */
    @Test
    void disabledByDefaultDoesNothing() {
        tenderWithLot(TechSpecStatus.PENDING);
        lotRepository.flush();

        scheduler.tick();

        verify(techSpecService, never()).parse(anyLong());
    }
}
