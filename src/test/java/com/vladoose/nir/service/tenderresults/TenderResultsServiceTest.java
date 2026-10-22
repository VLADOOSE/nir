package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.GoszakupCallException;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.dto.ContractRow;
import com.vladoose.nir.integration.goszakup.dto.ContractUnitRow;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Проход итогов (спека 2026-10-22-tender-results-market-prices §4, §5, §7): фейк площадки, PDF протокола, фиксированные часы. */
@SpringBootTest
@Transactional
class TenderResultsServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);   // 10:00 по Уральску = NOW

    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotResultRepository results;
    @Autowired TenderLotBidRepository bids;
    @Autowired TenderResultEventRepository events;
    @Autowired GoszakupSubjectRepository subjectRepo;
    @Autowired TenderResultsWriter writer;
    @Autowired JdbcTemplate jdbc;

    FakeGoszakupResultsClient fake;
    TenderResultsService service;
    String our = "999999999999";
    final List<String> imported = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        // nirdb постоянная: «первый проход» решает наличие строк итогов — чистим в транзакции теста (откат после)
        events.deleteAllInBatch();
        results.deleteAllInBatch();
        fake = new FakeGoszakupResultsClient();
        GoszakupSubjects subjects = new GoszakupSubjects(fake, subjectRepo, CLOCK, ms -> { });
        service = new TenderResultsService(fake, writer, subjects, () -> our, anno -> {
            imported.add(anno);
            return -1L;
        }, CLOCK, ms -> { });
    }

    @AfterEach void clear() { MarketContext.clear(); }

    /** Строка итогов уже есть — проход не первый, события не молчат. */
    private void notFirst() {
        Tender t = ResultsFixtures.tender(tenders, 970009999L, NOW.minusDays(40), "0-ЗЦП1", "Прошлый");
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(0).getId()).tenderId(t.getId())
                .outcome(LotOutcome.DONE).finalAt(OffsetDateTime.now(CLOCK)).eventAt(OffsetDateTime.now(CLOCK)).build());
    }

    private Tender zcp(long buyId, String code, int status, String protocol, String region) throws Exception {
        Tender t = ResultsFixtures.tenderIn(tenders, buyId, NOW.minusDays(8), region, code, "Тележка медицинская");
        String url = URL + buyId;
        fake.buys.put(buyId, buy(buyId, "2026-10-21 12:54:49", url, lot(43500000L + buyId % 1000, code, status, 88000000L + buyId % 1000)));
        if (protocol != null) fake.files.put(url, protocol.endsWith(".txt") ? protocolPdf(protocol) : protocol.getBytes(StandardCharsets.UTF_8));
        return t;
    }

    private ImportSummary check(Tender t) {
        ImportSummary sum = new ImportSummary();
        service.check(t.getId(), sum);
        return sum;
    }

    private TenderLotResult result(Tender t) {
        return results.findByTenderLotId(t.getLots().get(0).getId()).orElseThrow();
    }

    @Test
    void done_bidsWinnerRange_digestForOurLot() throws Exception {
        notFirst();
        Tender t = zcp(970001001L, "88214663-ЗЦП2", 360, "zcp-winner.txt", "Западно-Казахстанская область");
        markOurs(jdbc, t.getLots().get(0));
        ImportSummary sum = check(t);
        assertThat(sum.getErrors()).isZero();
        TenderLotResult r = result(t);
        assertThat(r.getOutcome()).isEqualTo(LotOutcome.DONE);
        assertThat(r.getResultsAt()).isEqualTo(LocalDateTime.of(2026, 10, 21, 12, 54, 49));
        assertThat(r.getProtocolStatus()).isEqualTo(ProtocolStatus.PARSED);
        assertThat(r.getBidsCount()).isEqualTo(10);
        assertThat(r.getWinnerName()).isEqualTo("Поставщик 1");
        assertThat(r.getWinnerPrice()).isEqualByComparingTo("251000");
        assertThat(r.getSecondBin()).isEqualTo("900000000002");
        assertThat(r.getMinPrice()).isEqualByComparingTo("251000");
        assertThat(r.getMaxPrice()).isEqualByComparingTo("613500");
        assertThat(bids.findByResultIdOrderByPlaceAscIdAsc(r.getId())).hasSize(10);
        TenderLot lot = lots.findById(t.getLots().get(0).getId()).orElseThrow();
        assertThat(lot.getExtLotId()).isNotNull();
        assertThat(lot.getEnstruCode()).isEqualTo("309911.100.000001");
        assertThat(lot.getPlanUnit()).isEqualTo("Штука");
        List<TenderResultEvent> ev = events.findByTenderIdOrderByIdAsc(t.getId());
        assertThat(ev).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.DONE);
        assertThat(ev.get(0).getTopic()).isEqualTo(ResultTopic.PRODUCTS);
        assertThat(ev.get(0).isUrgent()).isFalse();
        assertThat(ev.get(0).getDigestedAt()).as("ждёт сводки").isNull();
        assertThat(ev.get(0).getText()).startsWith("победил Поставщик 1 — 251\u00A0000\u00A0₸ за шт · 10 заявок (251–613\u00A0тыс.)");
    }

    /** «Звонить» по чужому лоту ЗКО — сразу в тему «Тендеры» (спека §7, вариант B). */
    @Test
    void noBids_zkoNotOurs_urgentToTenders() throws Exception {
        notFirst();
        Tender t = zcp(970001002L, "88208881-ЗЦП1", 370, "zcp-no-bids.txt", "Западно-Казахстанская область");
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getFailReason()).isEqualTo(FailReason.NO_BIDS);
        TenderResultEvent e = events.findByTenderIdOrderByIdAsc(t.getId()).get(0);
        assertThat(e.getKind()).isEqualTo(ResultEventKind.NO_BIDS);
        assertThat(e.isUrgent()).isTrue();
        assertThat(e.getTopic()).isEqualTo(ResultTopic.TENDERS);
        assertThat(e.getNotifiedAt()).as("ждёт отправки").isNull();
        assertThat(e.getText()).isEqualTo("никто не подал — объявят заново");
    }

    /** Чужой лот не из ЗКО — только «История». */
    @Test
    void noBids_outsideZko_historyOnly() throws Exception {
        notFirst();
        Tender t = zcp(970001003L, "88208881-ЗЦП1", 370, "zcp-no-bids.txt", "г. Астана");
        check(t);
        TenderResultEvent e = events.findByTenderIdOrderByIdAsc(t.getId()).get(0);
        assertThat(e.getTopic()).isEqualTo(ResultTopic.NONE);
        assertThat(e.getNotifiedAt()).isNotNull();
        assertThat(e.getDigestedAt()).isNotNull();
    }

    /** Наша заявка в протоколе — сразу, тема нашей продукции, по любому лоту (спека §7). */
    @Test
    void ourBid_place2_urgentProducts() throws Exception {
        notFirst();
        our = "900000000002";
        Tender t = zcp(970001004L, "88214663-ЗЦП2", 360, "zcp-winner.txt", "г. Астана");
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getOurPlace()).isEqualTo(2);
        assertThat(r.getOurPrice()).isNotNull();
        assertThat(r.getOurRejected()).isFalse();
        List<TenderResultEvent> ev = events.findByTenderIdOrderByIdAsc(t.getId());
        assertThat(ev).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.DONE, ResultEventKind.OUR_BID);
        TenderResultEvent bid = ev.get(1);
        assertThat(bid.getTopic()).isEqualTo(ResultTopic.PRODUCTS);
        assertThat(bid.isUrgent()).isTrue();
        assertThat(bid.getNotifiedAt()).isNull();
        assertThat(bid.getText()).startsWith("📉 Наше место 2 из 10: наша цена").contains("победил Поставщик 1");
        assertThat(ev.get(0).getTopic()).as("итог чужого лота не из ЗКО — без сообщения").isEqualTo(ResultTopic.NONE);
    }

    /** Первый проход — молча (спека §7). */
    @Test
    void firstRun_silent() throws Exception {
        Tender t = zcp(970001005L, "88208881-ЗЦП1", 370, "zcp-no-bids.txt", "Западно-Казахстанская область");
        ImportSummary sum = check(t);
        TenderResultEvent e = events.findByTenderIdOrderByIdAsc(t.getId()).get(0);
        assertThat(e.getNotifiedAt()).isNotNull();
        assertThat(sum.getMessage()).contains("первый проход");
    }

    /** 403 посреди прохода — стоп, ничего не окончательно и не потеряно; следующий проход доделывает (Review Focus 2). */
    @Test
    void forbiddenMidPass_stops_nothingLost() throws Exception {
        notFirst();
        Tender t = zcp(970001006L, "88208881-ЗЦП1", 370, "zcp-no-bids.txt", "Западно-Казахстанская область");
        fake.forbidFromCall = 2;                       // 1 — объявления, 2 — файл протокола
        ImportSummary sum = check(t);
        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getLastError()).contains("403");
        TenderLotResult r = result(t);
        assertThat(r.getOutcome()).isEqualTo(LotOutcome.FAILED);
        assertThat(r.getProtocolAttempts()).as("стоп-код попыткой не считается").isZero();
        assertThat(r.getFinalAt()).isNull();
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).isEmpty();
        fake.forbidFromCall = 0;
        check(t);
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.NO_BIDS);
    }

    /** Повторные проверки — одно событие, протокол качается один раз (Review Focus 3). */
    @Test
    void repeatedChecks_oneEvent_oneDownload() throws Exception {
        notFirst();
        Tender t = zcp(970001007L, "88208881-ЗЦП1", 370, "zcp-no-bids.txt", "Западно-Казахстанская область");
        check(t);
        check(t);
        check(t);
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).hasSize(1);
        assertThat(fake.calls.stream().filter(c -> c.startsWith("file:")).count()).isEqualTo(1);
    }

    /** Незнакомый протокол — пометка и ссылка, а «звонить» всё равно приходит (Review Focus 1). */
    @Test
    void unknownProtocol_stillCallSignal() throws Exception {
        notFirst();
        Tender t = zcp(970001008L, "1-ЭА1", 370, "<html><body><p>Протокол об итогах электронного аукциона № 1-ЭА1</p></body></html>",
                "Западно-Казахстанская область");
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getProtocolStatus()).isEqualTo(ProtocolStatus.UNKNOWN_FORMAT);
        assertThat(r.getProtocolNote()).contains("не знаком");
        assertThat(r.getProtocolUrl()).startsWith(URL);
        TenderResultEvent e = events.findByTenderIdOrderByIdAsc(t.getId()).get(0);
        assertThat(e.getKind()).isEqualTo(ResultEventKind.FAILED_OTHER);
        assertThat(e.isUrgent()).isTrue();
        assertThat(e.getText()).isEqualTo("не состоялся (протокол не разобран) — объявят заново");
    }

    /** Файл не качается — до 5 попыток, потом событие без протокола. */
    @Test
    void downloadFailures_fiveAttempts_thenSettles() throws Exception {
        notFirst();
        Tender t = zcp(970001009L, "88208881-ЗЦП1", 370, "zcp-no-bids.txt", "Западно-Казахстанская область");
        fake.downloadError = new GoszakupCallException("goszakup: протокол — HTTP 500", 500, true, null);
        for (int i = 0; i < 4; i++) check(t);
        assertThat(result(t).getProtocolAttempts()).isEqualTo(4);
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).isEmpty();
        ImportSummary sum = check(t);
        assertThat(sum.getErrors()).as("протокол — не ошибка прохода (решение 6)").isZero();
        assertThat(result(t).getProtocolStatus()).isEqualTo(ProtocolStatus.FAILED);
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).extracting(TenderResultEvent::getKind)
                .containsExactly(ResultEventKind.FAILED_OTHER);
    }

    /** Договор: последний подписанный, «не заключён» — мимо; итог окончательный, событие с названием поставщика. */
    @Test
    void contract_latestConcluded_final() throws Exception {
        notFirst();
        Tender t = zcp(970001010L, "88214663-ЗЦП2", 360, "zcp-winner.txt", "Западно-Казахстанская область");
        long extLot = 43500000L + 10;
        fake.contracts.add(new ContractRow(1L, 970001010L, "070740006294/260209/00", "2026-10-20 09:28:34", 190, "900000000001",
                new BigDecimal("753000"), List.of(new ContractUnitRow(extLot, new BigDecimal("251000"), new BigDecimal("3"), new BigDecimal("753000")))));
        fake.contracts.add(new ContractRow(2L, 970001010L, "НЕ-ТОТ", "2026-10-21 09:00:00", 330, "900000000002",
                new BigDecimal("1"), List.of(new ContractUnitRow(extLot, new BigDecimal("1"), new BigDecimal("3"), new BigDecimal("3")))));
        fake.subjects.put("900000000001", new SubjectInfo("900000000001", "ТОО «Поставщик»", null, null));
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getContractNumber()).isEqualTo("070740006294/260209/00");
        assertThat(r.getContractSignedAt()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(r.getContractUnitPrice()).isEqualByComparingTo("251000");
        assertThat(r.getFinalAt()).isNotNull();
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).extracting(TenderResultEvent::getKind)
                .containsExactly(ResultEventKind.CONTRACT, ResultEventKind.DONE);
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId()).get(0).getText()).startsWith("договор с ТОО «Поставщик»");
    }

    @Test
    void cancelled_finalWithoutProtocol() throws Exception {
        notFirst();
        Tender t = zcp(970001011L, "1-ЗЦП1", 430, null, "Западно-Казахстанская область");
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getOutcome()).isEqualTo(LotOutcome.CANCELLED);
        assertThat(r.getFinalAt()).isNotNull();
        assertThat(fake.calls).noneMatch(c -> c.startsWith("file:"));
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.CANCELLED);
    }

    @Test
    void pending_noEventNoDownload() throws Exception {
        notFirst();
        Tender t = zcp(970001012L, "1-ЗЦП1", 220, "zcp-no-bids.txt", "Западно-Казахстанская область");
        check(t);
        assertThat(result(t).getOutcome()).isEqualTo(LotOutcome.PENDING);
        assertThat(fake.calls).noneMatch(c -> c.startsWith("file:"));
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).isEmpty();
    }

    /** ОИ, у лота нет количества — цены за единицу нет, сумма есть (Review Focus 4). */
    @Test
    void oiDone_noQuantity_unitPriceEmpty() throws Exception {
        notFirst();
        Tender t = zcp(970001013L, "88138321-ОИ2", 360, null, "Западно-Казахстанская область");
        t.getLots().get(0).setQuantity(null);
        tenders.saveAndFlush(t);
        fake.files.put(URL + 970001013L, getClass().getResourceAsStream("/goszakup/protocols/oi-done.html").readAllBytes());
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getWinnerBin()).isEqualTo("900000000001");
        assertThat(r.getWinnerPrice()).isNull();
        assertThat(bids.findByResultIdOrderByPlaceAscIdAsc(r.getId()).get(0).getTotal()).isEqualByComparingTo("43000");
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.OI_DONE);
    }

    /** Проход по расписанию: свежий срок — в работе; итог, ждущий дольше 30 дней от срока, — закрыт. */
    @Test
    void run_processesFreshAndClosesStale() throws Exception {
        notFirst();
        Tender fresh = ResultsFixtures.tender(tenders, 970001014L, NOW.minusMinutes(1), "88208881-ЗЦП1", "Тележка");
        fake.buys.put(970001014L, buy(970001014L, "2026-10-22 09:59:00", URL + 970001014L, lot(43501014L, "88208881-ЗЦП1", 370, 1L)));
        fake.files.put(URL + 970001014L, protocolPdf("zcp-no-bids.txt"));
        Tender old = ResultsFixtures.tender(tenders, 970001015L, NOW.minusDays(40), "1-ЗЦП1", "Давно ждём");
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(old.getLots().get(0).getId()).tenderId(old.getId()).build());
        ImportSummary sum = new ImportSummary();
        service.run(sum);
        assertThat(events.findByTenderIdOrderByIdAsc(fresh.getId())).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.NO_BIDS);
        assertThat(results.findByTenderLotId(old.getLots().get(0).getId()).orElseThrow().getFinalAt()).isNotNull();
        assertThat(sum.getMessage()).startsWith("Итоги: тендеров проверено");
    }
}
