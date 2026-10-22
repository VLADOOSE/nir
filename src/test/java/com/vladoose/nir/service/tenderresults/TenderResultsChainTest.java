package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.dto.SubjectInfo;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.tenderresults.FakeGoszakupResultsClient;
import com.vladoose.nir.tenderresults.ResultsFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Цепочка попыток (спека 2026-10-22-tender-results-market-prices §6, решения 2–5). */
@SpringBootTest
@Transactional
class TenderResultsChainTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);
    static final String UNKNOWN = "<html><body><p>Протокол об итогах электронного аукциона № 1-ЭА1</p></body></html>";

    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotResultRepository results;
    @Autowired TenderResultEventRepository events;
    @Autowired GoszakupSubjectRepository subjectRepo;
    @Autowired TenderResultsWriter writer;
    @Autowired JdbcTemplate jdbc;

    FakeGoszakupResultsClient fake;
    TenderResultsService service;
    final List<String> imported = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        events.deleteAllInBatch();
        results.deleteAllInBatch();
        fake = new FakeGoszakupResultsClient();
        GoszakupSubjects subjects = new GoszakupSubjects(fake, subjectRepo, CLOCK, ms -> { });
        service = new TenderResultsService(fake, writer, subjects, () -> null, anno -> {
            imported.add(anno);
            return ResultsFixtures.tender(tenders, TenderResultsWriter.buyId(anno), NOW.plusDays(3), "9-ЗЦП9", "Повтор").getId();
        }, CLOCK, ms -> { });
        // не первый проход — события не молчат
        Tender t = ResultsFixtures.tender(tenders, 970009998L, NOW.minusDays(40), "0-ЗЦП1", "Прошлый");
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(0).getId()).tenderId(t.getId())
                .outcome(LotOutcome.DONE).finalAt(OffsetDateTime.now(CLOCK)).eventAt(OffsetDateTime.now(CLOCK)).build());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    /** Не состоявшийся лот: своя попытка — первая в цепочке (id лота площадки = ownLot). */
    private Tender failed(long buyId, String code, String protocol, String region) throws Exception {
        Tender t = ResultsFixtures.tenderIn(tenders, buyId, NOW.minusDays(8), region, code, "Тележка медицинская");
        String url = URL + buyId;
        fake.buys.put(buyId, buy(buyId, "2026-10-21 12:54:49", url, lot(ownLot(buyId), code, 370, point(buyId))));
        fake.files.put(url, protocol.endsWith(".txt") ? protocolPdf(protocol) : protocol.getBytes(StandardCharsets.UTF_8));
        return t;
    }

    private static long ownLot(long buyId) { return 43600000L + buyId % 1000; }
    private static long point(long buyId) { return 88600000L + buyId % 1000; }

    private List<TenderResultEvent> ev(Tender t) {
        return events.findByTenderIdOrderByIdAsc(t.getId());
    }

    private TenderLotResult result(Tender t) {
        return results.findByTenderLotId(t.getLots().get(0).getId()).orElseThrow();
    }

    /** Протокол не разобран, а сразу после провала — закупка из одного источника: значит, была одна заявка (решение 2). */
    @Test
    void oneBid_inferredFromOiAttempt() throws Exception {
        Tender t = failed(970002101L, "88111123-ЗЦП2", UNKNOWN, "Западно-Казахстанская область");
        fake.attempts.put(point(970002101L), List.of(
                attempt(ownLot(970002101L), "88111123-ЗЦП2", 370, point(970002101L), "970002101-1", "2026-10-16 10:00:00",
                        "2026-10-21 10:00:00", "2026-10-21 12:54:49", null),
                attempt(43699001L, "88111123-ОИ3", 220, point(970002101L), "970002102-1", "2026-10-21 12:55:30",
                        "2026-10-24 12:55:30", null, "900000000005")));
        fake.subjects.put("900000000005", new SubjectInfo("900000000005", "ИП БОЛАТ", null, null));
        service.check(t.getId(), new ImportSummary());
        assertThat(ev(t)).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.ONE_BID);
        assertThat(ev(t).get(0).getText()).isEqualTo("1 заявка → из одного источника у ИП БОЛАТ");
        assertThat(ev(t).get(0).isUrgent()).isFalse();
        assertThat(imported).as("закупку из одного источника не заводим (решение 5)").isEmpty();
        assertThat(result(t).getAttempts()).extracting(ResultAttempt::method).containsExactly("ЗЦП", "ОИ");
    }

    /** «Из одного источника сорвалось» по нашему лоту — «звонить» со звуком; повторный проход — без второго события. */
    @Test
    void oiFailed_callUrgentOnce() throws Exception {
        Tender t = failed(970002201L, "88111123-ЗЦП2", "zcp-one-bid.txt", "г. Астана");
        markOurs(jdbc, t.getLots().get(0));
        fake.attempts.put(point(970002201L), List.of(
                attempt(ownLot(970002201L), "88111123-ЗЦП2", 370, point(970002201L), "970002201-1", "2026-10-16 10:00:00",
                        "2026-10-21 10:00:00", "2026-10-21 12:54:49", null),
                attempt(43699002L, "88111123-ОИ3", 370, point(970002201L), "970002202-1", "2026-10-21 12:55:30",
                        "2026-10-24 12:55:30", "2026-10-22 09:00:00", "900000000006")));
        service.check(t.getId(), new ImportSummary());
        service.check(t.getId(), new ImportSummary());
        // цепочка идёт до событий итога — порядок строк не важен, «История» сортирует по времени площадки
        assertThat(ev(t)).extracting(TenderResultEvent::getKind).containsExactlyInAnyOrder(ResultEventKind.ONE_BID, ResultEventKind.OI_FAILED);
        TenderResultEvent call = ev(t).stream().filter(e -> e.getKind() == ResultEventKind.OI_FAILED).findFirst().orElseThrow();
        assertThat(call.isUrgent()).isTrue();
        assertThat(call.getTopic()).isEqualTo(ResultTopic.PRODUCTS);
        assertThat(call.getRefKey()).isEqualTo("970002202-1");
        assertThat(call.getText()).startsWith("из одного источника сорвалось (");
    }

    /** Повтор: событие один раз; запрос цен с идущим приёмом заводится в АИС, из одного источника — нет; дальше ведёт новый тендер. */
    @Test
    void repeat_eventOnce_importsRequestNotOi() throws Exception {
        Tender t = failed(970002301L, "88208881-ЗЦП1", "zcp-no-bids.txt", "Западно-Казахстанская область");
        fake.attempts.put(point(970002301L), List.of(
                attempt(ownLot(970002301L), "88208881-ЗЦП1", 370, point(970002301L), "970002301-1", "2026-10-16 10:00:00",
                        "2026-10-21 10:00:00", "2026-10-21 12:54:49", null),
                attempt(43699003L, "88208881-ЗЦП2", 220, point(970002301L), "970002302-1", "2026-10-21 15:00:00",
                        "2026-10-25 09:00:00", null, null),
                attempt(43699004L, "88208881-ОИ3", 220, point(970002301L), "970002303-1", "2026-10-21 16:00:00",
                        "2026-10-26 09:00:00", null, "900000000007")));
        service.check(t.getId(), new ImportSummary());
        assertThat(imported).containsExactly("970002302-1");
        assertThat(ev(t)).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.REPEAT, ResultEventKind.NO_BIDS);
        assertThat(ev(t).get(0).getText()).isEqualTo("объявлена повторная попытка № 970002302-1 (запрос цен), приём до 25.10 09:00");
        TenderLotResult r = result(t);
        Long repeatId = tenders.findBySourceExtId("970002302-1").orElseThrow().getId();
        assertThat(r.getNextTenderId()).isEqualTo(repeatId);
        assertThat(r.getFinalAt()).isNotNull();
        assertThat(r.getAttempts()).filteredOn(a -> "970002302-1".equals(a.anno())).extracting(ResultAttempt::tenderId).containsExactly(repeatId);
        assertThat(ev(t).get(0).getRefTenderId()).as("«История» ведёт на повтор").isEqualTo(repeatId);
    }

    /** Следующая попытка уже в АИС — итоги дальше ведёт она, у прошлой — ссылка и без событий цепочки (решение 3). */
    @Test
    void laterAttemptInAis_takesOver() throws Exception {
        Tender t = failed(970002401L, "88208881-ЗЦП1", "zcp-no-bids.txt", "Западно-Казахстанская область");
        Tender next = ResultsFixtures.tender(tenders, 970002402L, NOW.plusDays(2), "88208881-ЗЦП2", "Тележка");
        fake.attempts.put(point(970002401L), List.of(
                attempt(ownLot(970002401L), "88208881-ЗЦП1", 370, point(970002401L), "970002401-1", "2026-10-16 10:00:00",
                        "2026-10-21 10:00:00", "2026-10-21 12:54:49", null),
                attempt(43699005L, "88208881-ЗЦП2", 220, point(970002401L), "970002402-1", "2026-10-21 15:00:00",
                        "2026-10-24 09:00:00", null, null)));
        service.check(t.getId(), new ImportSummary());
        TenderLotResult r = result(t);
        assertThat(r.getNextTenderId()).isEqualTo(next.getId());
        assertThat(r.getFinalAt()).isNotNull();
        assertThat(ev(t)).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.NO_BIDS);
        assertThat(imported).isEmpty();
    }

    /** Повтор нашего лота — тоже наш: тема нашей продукции по пункту плана (решение 4). */
    @Test
    void oursInheritedByPlanPoint() throws Exception {
        Tender first = ResultsFixtures.tender(tenders, 970002501L, NOW.minusDays(20), "88208881-ЗЦП1", "Тележка");
        TenderLot firstLot = first.getLots().get(0);
        firstLot.setPlanPointId(point(970002502L));
        tenders.saveAndFlush(first);
        markOurs(jdbc, firstLot);
        Tender t = failed(970002502L, "88208881-ЗЦП1", "zcp-no-bids.txt", "г. Астана");   // лот — тот, что в протоколе фикстуры
        service.check(t.getId(), new ImportSummary());
        assertThat(ev(t).get(0).getKind()).isEqualTo(ResultEventKind.NO_BIDS);
        assertThat(ev(t).get(0).getTopic()).isEqualTo(ResultTopic.PRODUCTS);
    }

    /** Последняя попытка состоялась — цепочка окончательна; событие «из одного источника — состоялся» в сводку. */
    @Test
    void chainFinal_whenLastAttemptDone() throws Exception {
        Tender t = failed(970002601L, "88111123-ЗЦП2", "zcp-one-bid.txt", "Западно-Казахстанская область");
        markOurs(jdbc, t.getLots().get(0));
        fake.attempts.put(point(970002601L), List.of(
                attempt(ownLot(970002601L), "88111123-ЗЦП2", 370, point(970002601L), "970002601-1", "2026-10-16 10:00:00",
                        "2026-10-21 10:00:00", "2026-10-21 12:54:49", null),
                attempt(43699006L, "88111123-ОИ3", 360, point(970002601L), "970002602-1", "2026-10-21 12:55:30",
                        "2026-10-24 12:55:30", "2026-10-22 09:00:00", "900000000008")));
        service.check(t.getId(), new ImportSummary());
        assertThat(result(t).getFinalAt()).isNotNull();
        TenderResultEvent oi = ev(t).stream().filter(e -> e.getKind() == ResultEventKind.OI_DONE).findFirst().orElseThrow();
        assertThat(oi.isUrgent()).isFalse();
        assertThat(oi.getDigestedAt()).isNull();
    }
}
