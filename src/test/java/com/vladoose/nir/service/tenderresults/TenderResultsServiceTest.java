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

    /**
     * ОИ: «Цена поставщика» в протоколе — за единицу (сверено с договорами 2026-10-23), поэтому цена за единицу есть и у лота без
     * количества (дробное количество площадки). До 2026-10-23 её считали суммой лота и делили на количество — цена выходила в N раз меньше.
     */
    @Test
    void oiDone_unitPriceFromProtocol_evenWithoutQuantity() throws Exception {
        notFirst();
        Tender t = zcp(970001013L, "88138321-ОИ2", 360, null, "Западно-Казахстанская область");
        t.getLots().get(0).setQuantity(null);
        tenders.saveAndFlush(t);
        fake.files.put(URL + 970001013L, getClass().getResourceAsStream("/goszakup/protocols/oi-done.html").readAllBytes());
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getWinnerBin()).isEqualTo("900000000001");
        assertThat(r.getWinnerPrice()).isEqualByComparingTo("43000");
        assertThat(bids.findByResultIdOrderByPlaceAscIdAsc(r.getId()).get(0).getTotal()).isEqualByComparingTo("43000");
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.OI_DONE);
    }

    /** Коды и наименования лотов обезличенного протокола 17821999-ИОИ1 (7 лотов, 84956065-ОИ2 не состоялся). */
    private static final String[] OI_LOTS = {"84956222-ОИ2", "Индикатор", "84954657-ОИ2", "Среда питательная", "84954982-ОИ2",
            "Добавка селективная", "84955582-ОИ2", "Диагностикум", "84956014-ОИ2", "Среда питательная", "84956065-ОИ2", "Диск",
            "84956100-ОИ2", "Диск"};

    /** Тендер ОИ на 7 лотов: объявление и файл протокола (PDF из текстовой фикстуры) — у фейка площадки. */
    private Tender oiMultiLot(long buyId) throws Exception {
        Tender t = ResultsFixtures.tender(tenders, buyId, NOW.minusDays(8), OI_LOTS);
        List<com.vladoose.nir.integration.goszakup.dto.ResultLot> lotsOnPlatform = new ArrayList<>();
        for (int i = 0; i < OI_LOTS.length; i += 2) {
            lotsOnPlatform.add(lot(43600000L + i, OI_LOTS[i], OI_LOTS[i].equals("84956065-ОИ2") ? 370 : 360, 88100000L + i));
        }
        fake.buys.put(buyId, buy(buyId, "2026-10-21 09:41:07", URL + buyId,
                lotsOnPlatform.toArray(com.vladoose.nir.integration.goszakup.dto.ResultLot[]::new)));
        fake.files.put(URL + buyId, protocolPdf("oi-multi-lot.txt"));
        return t;
    }

    private TenderLotResult result(Tender t, String code) {
        TenderLot lot = t.getLots().stream().filter(l -> l.getSourceLotCode().equals(code)).findFirst().orElseThrow();
        return results.findByTenderLotId(lot.getId()).orElseThrow();
    }

    /** Протокол из одного источника на несколько лотов — у каждого лота свой итог (живая проверка 2026-10-23). */
    @Test
    void oiMultiLot_everyLotParsed() throws Exception {
        notFirst();
        Tender t = oiMultiLot(970001016L);
        check(t);
        assertThat(results.findByTenderId(t.getId())).hasSize(7)
                .allSatisfy(r -> assertThat(r.getProtocolStatus()).isEqualTo(ProtocolStatus.PARSED));
        TenderLotResult twoPcs = result(t, "84954657-ОИ2");
        assertThat(twoPcs.getWinnerName()).isEqualTo("Товарищество с ограниченной ответственностью \"Поставщик 1\"");
        assertThat(twoPcs.getWinnerPrice()).isEqualByComparingTo("40200");
        TenderLotResult failed = result(t, "84956065-ОИ2");
        assertThat(failed.getOutcome()).isEqualTo(LotOutcome.FAILED);
        assertThat(failed.getWinnerBin()).isNull();
        assertThat(failed.getFailText()).contains("не предоставил в установленный срок ответ");
        assertThat(fake.calls.stream().filter(c -> c.startsWith("file:")).count()).isEqualTo(1);
        // в тексте итога — форма собственности коротко, как у обычных итогов (прод 2026-10-24: «у Товарищества с ограниченной …»)
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).filteredOn(e -> e.getKind() == ResultEventKind.OI_DONE).hasSize(6)
                .extracting(TenderResultEvent::getText).containsOnly("из одного источника у ТОО \"Поставщик 1\" — состоялся");
    }

    /**
     * Итоги ОИ, записанные прежним разбором, как на проде до V45: первый лот разобран (цена — сумма, поделённая на количество),
     * остальным — «лота нет в протоколе»; итоги окончательные, события заведены; пометка V45 — к переразбору.
     */
    private Tender oiRecordedByOldParser(long buyId) throws Exception {
        Tender t = oiMultiLot(buyId);
        OffsetDateTime at = OffsetDateTime.now(CLOCK).minusDays(1);
        for (TenderLot l : t.getLots()) {
            boolean first = l.getSourceLotCode().equals("84956222-ОИ2");
            results.saveAndFlush(TenderLotResult.builder().tenderLotId(l.getId()).tenderId(t.getId())
                    .outcome(l.getSourceLotCode().equals("84956065-ОИ2") ? LotOutcome.FAILED : LotOutcome.DONE)
                    .protocolUrl(URL + buyId).protocolStatus(first ? ProtocolStatus.PARSED : ProtocolStatus.UNKNOWN_FORMAT)
                    .protocolNote(first ? null : "лота нет в протоколе — откройте файл")
                    .winnerBin(first ? "900000000001" : null).winnerPrice(first ? new BigDecimal("1933.33") : null)
                    .finalAt(at).eventAt(at).protocolReparse(true).build());
        }
        return t;
    }

    private long downloads() {
        return fake.calls.stream().filter(c -> c.startsWith("file:")).count();
    }

    /** V45: записанные итоги ОИ — проход один раз скачивает протокол и разбирает его заново, окончательные тоже. */
    @Test
    void reparse_recordedOiResults_rewrittenOnce() throws Exception {
        notFirst();
        Tender t = oiRecordedByOldParser(970001017L);
        ImportSummary sum = check(t);
        assertThat(sum.getErrors()).isZero();
        assertThat(results.findByTenderId(t.getId())).hasSize(7).allSatisfy(r -> {
            assertThat(r.getProtocolStatus()).isEqualTo(ProtocolStatus.PARSED);
            assertThat(r.getProtocolNote()).isNull();
            assertThat(r.isProtocolReparse()).isFalse();
        });
        TenderLotResult first = result(t, "84956222-ОИ2");
        assertThat(first.getWinnerPrice()).isEqualByComparingTo("5800");
        assertThat(first.getWinnerName()).isEqualTo("Товарищество с ограниченной ответственностью \"Поставщик 1\"");
        TenderLotResult twoPcs = result(t, "84954657-ОИ2");
        assertThat(twoPcs.getWinnerPrice()).isEqualByComparingTo("40200");
        assertThat(bids.findByResultIdOrderByPlaceAscIdAsc(twoPcs.getId())).extracting(TenderLotBid::getTotal)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class).containsExactly(new BigDecimal("80400"));
        assertThat(result(t, "84956065-ОИ2").getFailText()).contains("не предоставил в установленный срок ответ");
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).as("события уже были — второй раз не заводятся").isEmpty();
        check(t);
        assertThat(downloads()).as("протокол скачан один раз").isEqualTo(1);
    }

    /** Переразбор не удался — прежний итог остаётся, пометка снимается (файл не качается каждый час); не ошибка прохода. */
    @Test
    void reparse_downloadFails_oldResultKept_flagCleared() throws Exception {
        notFirst();
        Tender t = oiRecordedByOldParser(970001018L);
        fake.downloadError = new GoszakupCallException("goszakup: протокол — HTTP 500", 500, true, null);
        ImportSummary sum = check(t);
        assertThat(sum.getErrors()).isZero();
        TenderLotResult first = result(t, "84956222-ОИ2");
        assertThat(first.getWinnerPrice()).isEqualByComparingTo("1933.33");
        assertThat(first.getProtocolStatus()).isEqualTo(ProtocolStatus.PARSED);
        assertThat(result(t, "84954657-ОИ2").getProtocolStatus()).isEqualTo(ProtocolStatus.UNKNOWN_FORMAT);
        assertThat(result(t, "84954657-ОИ2").getProtocolNote()).isEqualTo("лота нет в протоколе — откройте файл");
        assertThat(results.findByTenderId(t.getId())).allSatisfy(r -> assertThat(r.isProtocolReparse()).isFalse());
        long before = downloads();
        fake.downloadError = null;
        check(t);
        assertThat(downloads()).isEqualTo(before);
    }

    /** 403 при переразборе — проход стоп, пометка ждёт следующего прохода; он и разбирает. */
    @Test
    void reparse_forbidden_flagStaysForNextPass() throws Exception {
        notFirst();
        Tender t = oiRecordedByOldParser(970001019L);
        fake.forbidFromCall = 2;                       // 1 — объявления, 2 — файл протокола
        ImportSummary sum = check(t);
        assertThat(sum.getLastError()).contains("403");
        assertThat(results.findByTenderId(t.getId())).allSatisfy(r -> assertThat(r.isProtocolReparse()).isTrue());
        fake.forbidFromCall = 0;
        check(t);
        assertThat(result(t, "84954657-ОИ2").getWinnerPrice()).isEqualByComparingTo("40200");
        assertThat(results.findByTenderId(t.getId())).allSatisfy(r -> assertThat(r.isProtocolReparse()).isFalse());
    }

    /** Проход по расписанию переразбирает и итоги тендеров, которые уже не кандидаты (срок дальше 45 дней), — как на проде после V45. */
    @Test
    void reparse_scheduledPass_coversOldTenders() throws Exception {
        notFirst();
        Tender t = oiRecordedByOldParser(970001020L);
        t.setDeadlineAt(NOW.minusDays(60));
        t.setDeadline(NOW.minusDays(60).toLocalDate());
        tenders.saveAndFlush(t);
        ImportSummary sum = new ImportSummary();
        service.run(sum);
        assertThat(fake.calls).noneMatch(c -> c.startsWith("buys:") && c.contains("970001020"));
        assertThat(result(t, "84954657-ОИ2").getWinnerPrice()).isEqualByComparingTo("40200");
        assertThat(results.findByTenderId(t.getId())).allSatisfy(r -> assertThat(r.isProtocolReparse()).isFalse());
    }

    /** Конкурс на один лот: количество и бюджет за единицу — как в протоколе; объявление и файл (обезличенный живой HTML) — у фейка. */
    private Tender konkurs(long buyId, String code, String name, int qty, String budget, String protocol) throws Exception {
        Tender t = ResultsFixtures.tender(tenders, buyId, NOW.minusDays(8), code, name);
        t.getLots().get(0).setQuantity(qty);
        t.getLots().get(0).setMaxCost(new BigDecimal(budget));
        t = tenders.saveAndFlush(t);
        fake.buys.put(buyId, buy(buyId, "2026-10-21 12:54:49", URL + buyId, lot(43700000L + buyId % 1000, code, 360, 88200000L + buyId % 1000)));
        fake.files.put(URL + buyId, getClass().getResourceAsStream("/goszakup/protocols/" + protocol).readAllBytes());
        return t;
    }

    /** Конкурс с одной заявкой (насос, 2 шт.): победитель — из «заключить договор … с», цена — за единицу, как в договоре площадки. */
    @Test
    void konkursSingleBid_winnerAndUnitPrice() throws Exception {
        notFirst();
        Tender t = konkurs(970001021L, "87829972-ОК1", "Насос", 2, "7000000", "konkurs-single-bid.html");
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getProtocolStatus()).isEqualTo(ProtocolStatus.PARSED);
        assertThat(r.getWinnerBin()).isEqualTo("900000000001");
        assertThat(r.getWinnerName()).isEqualTo("ИП \"Поставщик 1\"");
        assertThat(r.getWinnerPrice()).isEqualByComparingTo("6700000");
        assertThat(bids.findByResultIdOrderByPlaceAscIdAsc(r.getId())).extracting(TenderLotBid::getTotal)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class).containsExactly(new BigDecimal("13400000"));
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).extracting(TenderResultEvent::getText)
                .singleElement().asString().startsWith("победил ИП \"Поставщик 1\" — " + ResultTexts.money(new BigDecimal("6700000")) + " за шт");
    }

    /**
     * V46: итог конкурса, записанный прежним разбором, как на проде (победитель — ИИН бенефициара, цена — поделённая на количество,
     * событие «Итоги» заведено), переразбирается: победитель и цена — из протокола, текст события в «Истории» — новый, второго нет.
     */
    @Test
    void reparse_recordedKonkurs_winnerPriceFixed_eventTextRefreshed() throws Exception {
        notFirst();
        Tender t = konkurs(970001022L, "87170063-ОК1", "Планшет", 10, "351500", "konkurs-beneficiary.html");
        TenderLot lot = t.getLots().get(0);
        OffsetDateTime at = OffsetDateTime.now(CLOCK).minusDays(1);
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(lot.getId()).tenderId(t.getId()).outcome(LotOutcome.DONE)
                .protocolUrl(URL + 970001022L).protocolStatus(ProtocolStatus.PARSED).winnerBin("800000000001")
                .winnerPrice(new BigDecimal("33392.50")).bidsCount(2).finalAt(at).eventAt(at).protocolReparse(true).build());
        events.saveAndFlush(TenderResultEvent.builder().tenderId(t.getId()).tenderLotId(lot.getId()).kind(ResultEventKind.DONE)
                .occurredAt(at.toLocalDateTime()).text("победил БИН 800000000001 — 33 392,50 ₸ за шт · 2 заявки").urgent(false)
                .topic(ResultTopic.NONE).createdAt(at).notifiedAt(at).digestedAt(at).build());
        ImportSummary sum = check(t);
        assertThat(sum.getErrors()).isZero();
        TenderLotResult r = result(t);
        assertThat(r.isProtocolReparse()).isFalse();
        assertThat(r.getWinnerBin()).isEqualTo("900000000001");
        assertThat(r.getWinnerName()).isEqualTo("ТОО \"Поставщик 1\"");
        assertThat(r.getWinnerPrice()).isEqualByComparingTo("333925");
        assertThat(r.getSecondBin()).isEqualTo("900000000002");
        List<TenderResultEvent> ev = events.findByTenderIdOrderByIdAsc(t.getId());
        assertThat(ev).as("событие уже было — второе не заводится").hasSize(1);
        assertThat(ev.get(0).getText()).startsWith("победил ТОО \"Поставщик 1\" — " + ResultTexts.money(new BigDecimal("333925")) + " за шт")
                .doesNotContain("800000000001");
        assertThat(ev.get(0).getNotifiedAt()).as("сообщение не повторяется").isNotNull();
        assertThat(ev.get(0).getDigestedAt()).isNotNull();
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
