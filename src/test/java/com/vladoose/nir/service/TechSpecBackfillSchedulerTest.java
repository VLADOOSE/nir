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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
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
        // ⚠ batch-size=1 НЕ ПОДНИМАТЬ: тесты опираются на то, что пачка = ровно свежесозданный
        // лот (у него максимальный id, а запрос идёт ORDER BY id DESC). С большим размером в
        // пачку попадут реальные лоты nirdb, и ассерты начнут зависеть от содержимого базы.
        // Где нужна пачка побольше — точечный ReflectionTestUtils, см. outage-тест.
        "techspec.backfill.batch-size=1",
        "techspec.backfill.throttle-ms=0"   // без пауз
})
class TechSpecBackfillSchedulerTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired TechSpecBackfillScheduler scheduler;
    @PersistenceContext EntityManager em;

    /** Шпион, а не мок: методы настоящие, но в одном тесте нужно заставить запись исхода упасть. */
    @MockitoSpyBean TechSpecStatusWriter writer;

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

    // ---------- C1: Error не должен запирать очередь ----------

    /**
     * OutOfMemoryError на PDFBox — наблюдавшееся состояние этого проекта (§5/§16), а не выдумка.
     * Он обязан (1) записать исход, иначе лот навсегда остался бы PENDING и следующий тик выбрал
     * бы ТОТ ЖЕ лот с тем же PDF, и (2) всё равно уйти наружу — глушить Error мы не вправе.
     */
    @Test
    void fatalErrorRecordsOutcomeAndStillPropagates() {
        Tender t = tenderWithLot(TechSpecStatus.PENDING);
        Long id = lotId(t);
        lotRepository.flush();
        when(techSpecService.parse(id)).thenThrow(new OutOfMemoryError("Java heap space"));

        assertThatThrownBy(scheduler::runOnce).isInstanceOf(OutOfMemoryError.class);
        MarketContext.set(Market.KZ);

        assertThat(reload(id).getTechSpecStatus()).isEqualTo(TechSpecStatus.ERROR);
        assertThat(lotRepository.findPendingTechSpec(Market.KZ, PageRequest.of(0, 50)))
                .doesNotContain(id);
    }

    /**
     * И главное: следующая пачка берёт СЛЕДУЮЩИЙ лот, а не спотыкается о тот же самый.
     *
     * <p>Здесь намеренно НЕ настоящий {@link OutOfMemoryError}, а свой подкласс Error: JUnit
     * считает настоящий OOM неустранимым и убивает воркер, поэтому регрессия проявилась бы не
     * читаемым падением теста, а обрывом всего прогона. Ветка кода проверяется та же — catch(Error).
     */
    @Test
    void queueAdvancesToNextLotAfterFatalError() {
        Tender earlier = tenderWithLot(TechSpecStatus.PENDING);
        Tender exploding = tenderWithLot(TechSpecStatus.PENDING);   // id выше → берётся первым
        lotRepository.flush();
        when(techSpecService.parse(lotId(exploding))).thenThrow(new FatalPdfError());

        assertThatThrownBy(scheduler::runOnce).isInstanceOf(FatalPdfError.class);
        MarketContext.set(Market.KZ);
        runQueue();   // второй тик

        verify(techSpecService).parse(lotId(earlier));
        assertThat(reload(lotId(earlier)).getTechSpecStatus()).isEqualTo(TechSpecStatus.OK);
    }

    /** Стенд-ин для OOM из PDFBox: тот же catch(Error), но не убивает тестовый воркер. */
    private static class FatalPdfError extends Error {
        FatalPdfError() { super("PDF не поместился в кучу (стенд-ин OutOfMemoryError)"); }
    }

    /**
     * SIGKILL (exit 137 от OOM-киллера контейнера) не даёт выполниться НИ ОДНОЙ строке Java —
     * никакой catch тут не поможет. Поэтому попытка отмечается ДО разбора: проверяем, что в
     * момент работы парсера в БД уже стоит отметка. Если бы контейнер убили ровно здесь, лот не
     * выбрался бы после рестарта снова (иначе — крэш-луп: тот же лот, тот же PDF, каждый тик),
     * а вернулся бы в очередь через requeueStaleErrors.
     */
    @Test
    void attemptIsRecordedBeforeParseStarts() {
        Tender t = tenderWithLot(TechSpecStatus.PENDING);
        Long id = lotId(t);
        lotRepository.flush();
        AtomicReference<TechSpecStatus> duringParse = new AtomicReference<>();
        when(techSpecService.parse(id)).thenAnswer(inv -> {
            duringParse.set(reload(id).getTechSpecStatus());
            return null;
        });

        runQueue();

        assertThat(duringParse.get()).isEqualTo(TechSpecStatus.ERROR);   // попытка уже записана
        assertThat(reload(id).getTechSpecStatus()).isEqualTo(TechSpecStatus.OK);  // успех перекрыл
    }

    /** Убитый на разборе лот не остаётся в очереди — но и не теряется: возврат его подберёт. */
    @Test
    void lotKilledMidParseLeavesQueueAndComesBackLater() {
        Tender t = tenderWithLot(TechSpecStatus.PENDING);
        Long id = lotId(t);
        lotRepository.flush();
        // Имитация SIGKILL: отметка «в работе» есть, исход записать не успели.
        writer.markResult(id, TechSpecStatus.ERROR);
        lotRepository.flush();

        assertThat(lotRepository.findPendingTechSpec(Market.KZ, PageRequest.of(0, 50)))
                .doesNotContain(id);          // сразу заново не выберется → крэш-лупа нет

        em.createQuery("UPDATE TenderLot l SET l.techSpecAttemptedAt = :old WHERE l.id = :id")
                .setParameter("old", OffsetDateTime.now().minusDays(30))
                .setParameter("id", id).executeUpdate();
        em.clear();
        runQueue();

        verify(techSpecService).parse(id);     // и не потерян: вернулся сам
    }

    /**
     * Флаг прерывания не должен переживать пачку: поток у воркера свой и вечный, а throttle()
     * флаг восстанавливает. Иначе каждая следующая пачка обрывалась бы на первом лоте, продолжая
     * писать «взято N лотов» — очередь стоит, а по логам здорова.
     */
    @Test
    void staleInterruptFlagDoesNotSilentlyStallQueue() {
        Tender t = tenderWithLot(TechSpecStatus.PENDING);
        lotRepository.flush();
        try {
            Thread.currentThread().interrupt();   // «остался от прошлой пачки»

            runQueue();

            verify(techSpecService).parse(lotId(t));
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            Thread.interrupted();   // не тащим флаг в следующие тесты
        }
    }

    /**
     * Падение записи исхода не должно ПОДМЕНИТЬ собой уже случившийся Error: JPA save аллоцирует,
     * то есть под настоящим исчерпанием кучи падает именно она, и наружу ушла бы ошибка записи —
     * а исходный OutOfMemoryError потерялся бы.
     */
    @Test
    void outcomeWriteFailureDoesNotMaskFatalError() {
        Tender t = tenderWithLot(TechSpecStatus.PENDING);
        Long id = lotId(t);
        lotRepository.flush();
        when(techSpecService.parse(id)).thenThrow(new FatalPdfError());
        doCallRealMethod()                                        // предварительная отметка
                .doThrow(new IllegalStateException("нет памяти на запись"))  // запись исхода
                .when(writer).markResult(anyLong(), any());

        assertThatThrownBy(scheduler::runOnce)
                .isInstanceOf(FatalPdfError.class)
                .hasSuppressedException(new IllegalStateException("нет памяти на запись"));
        MarketContext.set(Market.KZ);
    }

    // ---------- C2: авария площадки не должна сжигать очередь ----------

    /**
     * Три отказа подряд — пачка обрывается, остальные лоты НЕ тронуты (остаются PENDING).
     * Без предохранителя IP-блок прода списал бы в ERROR всю очередь за сутки.
     */
    @Test
    void outageTripsBreakerAndLeavesRestOfBatchPending() {
        ReflectionTestUtils.setField(scheduler, "batchSize", 5);
        try {
            Tender untouched = tenderWithLot(TechSpecStatus.PENDING);   // самый нижний id из четырёх
            Tender third = tenderWithLot(TechSpecStatus.PENDING);
            Tender second = tenderWithLot(TechSpecStatus.PENDING);
            Tender first = tenderWithLot(TechSpecStatus.PENDING);       // самый верхний id
            lotRepository.flush();
            when(techSpecService.parse(anyLong())).thenThrow(new UpstreamException("Connection refused"));

            runQueue();

            for (Tender burnt : List.of(first, second, third)) {
                assertThat(reload(lotId(burnt)).getTechSpecStatus()).isEqualTo(TechSpecStatus.ERROR);
            }
            verify(techSpecService, never()).parse(lotId(untouched));
            assertThat(reload(lotId(untouched)).getTechSpecStatus()).isEqualTo(TechSpecStatus.PENDING);
        } finally {
            ReflectionTestUtils.setField(scheduler, "batchSize", 1);
        }
    }

    /** Успех между отказами сбрасывает счётчик — предохранитель ловит АВАРИЮ, а не сумму бед. */
    @Test
    void successResetsOutageCounter() {
        ReflectionTestUtils.setField(scheduler, "batchSize", 4);
        try {
            Tender last = tenderWithLot(TechSpecStatus.PENDING);
            Tender good = tenderWithLot(TechSpecStatus.PENDING);
            Tender bad2 = tenderWithLot(TechSpecStatus.PENDING);
            Tender bad1 = tenderWithLot(TechSpecStatus.PENDING);
            lotRepository.flush();
            when(techSpecService.parse(lotId(bad1))).thenThrow(new UpstreamException("сеть"));
            when(techSpecService.parse(lotId(bad2))).thenThrow(new UpstreamException("сеть"));
            // good парсится успешно (мок по умолчанию), затем last

            runQueue();

            verify(techSpecService).parse(lotId(last));   // до конца пачки дошли
            assertThat(reload(lotId(good)).getTechSpecStatus()).isEqualTo(TechSpecStatus.OK);
        } finally {
            ReflectionTestUtils.setField(scheduler, "batchSize", 1);
        }
    }

    /**
     * Сгоревший лот сам возвращается в очередь: иначе одна внешняя авария была бы приговором —
     * восстановление требовало бы ручного SQL по боевой базе, и никакого сигнала об этом.
     */
    @Test
    void staleErrorReturnsToQueue() {
        Tender t = tenderWithLot(TechSpecStatus.ERROR);
        Long id = lotId(t);
        t.getLots().get(0).setTechSpecAttemptedAt(OffsetDateTime.now().minusDays(30));
        lotRepository.flush();

        runQueue();

        verify(techSpecService).parse(id);   // лот снова взят в работу
    }

    /** А свежая ошибка — не возвращается: это ретрай по таймауту, а не бесконечный цикл. */
    @Test
    void freshErrorStaysOutOfQueue() {
        Tender t = tenderWithLot(TechSpecStatus.ERROR);
        t.getLots().get(0).setTechSpecAttemptedAt(OffsetDateTime.now());
        lotRepository.flush();

        runQueue();

        verify(techSpecService, never()).parse(lotId(t));
    }

    // ---------- M1: работа оператора не затирается ----------

    /**
     * Оператор вписал ТЗ руками, пока лот был в работе (контроллер ставит OK) — фоновая неудача
     * НЕ понижает его отметку. Иначе переимпорт (гард Task 7 ключуется на status == OK) затёр бы
     * набранный текст описанием с площадки.
     */
    @Test
    void manualSpecIsNotDowngradedByWorkerOutcome() {
        Tender t = tenderWithLot(TechSpecStatus.OK);

        writer.markResult(lotId(t), TechSpecStatus.ERROR);

        assertThat(reload(lotId(t)).getTechSpecStatus()).isEqualTo(TechSpecStatus.OK);
    }
}
