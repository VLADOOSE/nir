package com.vladoose.nir.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** V43 (спека 2026-10-22-tender-results-market-prices §12): колонки, JSONB цепочки, «наш лот», кандидаты прохода. */
@SpringBootTest
@Transactional
class TenderResultsSchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotResultRepository results;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void v43Columns() {
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'tender_lot' "
                + "and column_name in ('ext_lot_id', 'plan_point_id', 'enstru_code', 'plan_unit')", String.class)).hasSize(4);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name in ('tender_lot_result', "
                + "'tender_lot_bid', 'tender_result_event', 'market_contract', 'market_lookup', 'goszakup_subject')", Integer.class))
                .isEqualTo(6);
    }

    @Test
    void attemptsRoundTrip() {
        Tender t = ResultsFixtures.tender(tenders, 970000001L, NOW.minusDays(2), "88214663-ЗЦП2", "Тележка");
        TenderLotResult r = results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(0).getId())
                .tenderId(t.getId()).outcome(LotOutcome.FAILED)
                .attempts(List.of(new ResultAttempt("17780275-1", "88208881-ЗЦП2", "ЗЦП", 370, "Закупка не состоялась",
                        "2026-10-14 11:42:00", "2026-10-16 20:19:00", null, null))).build());
        em.clear();
        assertThat(results.findById(r.getId()).orElseThrow().getAttempts()).extracting(ResultAttempt::anno).containsExactly("17780275-1");
    }

    /** Наш лот (спека §7): модель в работе / запрос цен / заявка — или отметка «наш товар» в тендере из пачки, кроме «не наше». */
    @Test
    void ourLots() {
        Tender t = ResultsFixtures.tender(tenders, 970000002L, NOW.minusDays(2),
                "1-ЗЦП1", "С моделью", "2-ЗЦП1", "С отметкой", "3-ЗЦП1", "ИИ: не наше", "4-ЗЦП1", "Без всего");
        t.setProductNotifiedLevel(ProductMatchLevel.TYPE);
        TenderLot a = t.getLots().get(0), b = t.getLots().get(1), c = t.getLots().get(2), d = t.getLots().get(3);
        c.setAiVerdict(AiVerdict.NO);
        tenders.saveAndFlush(t);
        Long eq = jdbc.queryForObject("insert into med_equipment (name, manufact, market) values ('ZZ модель', 'ZZ', 'KZ') returning id", Long.class);
        jdbc.update("update tender_lot set proposed_equipment_id = ? where id = ?", eq, a.getId());
        Long group = jdbc.queryForObject("insert into product_group (market, name) values ('KZ', 'ZZ группа') returning id", Long.class);
        for (TenderLot l : List.of(b, c)) {
            jdbc.update("insert into tender_lot_product_match (tender_id, lot_id, group_id, level, source) values (?, ?, ?, 'TYPE', 'SEARCH')",
                    t.getId(), l.getId(), group);
        }
        assertThat(lots.findOurLotIds(List.of(a.getId(), b.getId(), c.getId(), d.getId())))
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    /**
     * V45 помечает к переразбору итоги лотов «из одного источника», у которых есть файл и протокол разобран или «лота нет / вид не
     * знаком»; запрос цен, ОИ без файла и не скачанный (его повторяет обычный проход) — нет.
     */
    @Test
    void v45_marksRecordedOiProtocolsOnly() throws Exception {
        Tender t = ResultsFixtures.tender(tenders, 970000012L, NOW.minusDays(5), "1-ОИ2", "ОИ разобран", "2-ОИ2", "ОИ — лота нет",
                "3-ЗЦП1", "Запрос цен", "4-ОИ2", "ОИ — файла нет", "5-ОИ2", "ОИ — не скачан");
        ProtocolStatus[] status = {ProtocolStatus.PARSED, ProtocolStatus.UNKNOWN_FORMAT, ProtocolStatus.PARSED, ProtocolStatus.NO_FILE,
                ProtocolStatus.FAILED};
        for (int i = 0; i < status.length; i++) {
            results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(i).getId()).tenderId(t.getId())
                    .outcome(LotOutcome.DONE).protocolStatus(status[i]).protocolUrl(i == 3 ? null : ResultsFixtures.URL + i).build());
        }
        jdbc.update(v45Update());
        em.clear();
        assertThat(t.getLots().stream().map(l -> results.findByTenderLotId(l.getId()).orElseThrow().isProtocolReparse()))
                .containsExactly(true, true, false, false, false);
    }

    /** UPDATE из V45 — тот, что выполнил Flyway (колонка в nirdb уже есть). */
    private String v45Update() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V45__protocol_reparse.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return Arrays.stream(sql.split(";"))
                .map(s -> s.lines().filter(l -> !l.strip().startsWith("--")).collect(Collectors.joining("\n")).strip())
                .filter(s -> s.startsWith("UPDATE")).findFirst().orElseThrow();
    }

    /** Кандидаты прохода: срок прошёл не раньше 45 дней назад, есть лот без окончательного итога (нет строки итога или она не окончательная); частные заявки и будущие — нет. */
    @Test
    void candidates() {
        Tender passed = ResultsFixtures.tender(tenders, 970000003L, NOW.minusDays(2), "5-ЗЦП1", "Прошёл");
        Tender future = ResultsFixtures.tender(tenders, 970000004L, NOW.plusDays(1), "6-ЗЦП1", "Ещё идёт");
        Tender old = ResultsFixtures.tender(tenders, 970000005L, NOW.minusDays(60), "7-ЗЦП1", "Давно");
        Tender done = ResultsFixtures.tender(tenders, 970000006L, NOW.minusDays(3), "8-ЗЦП1", "Итог окончательный");
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(done.getLots().get(0).getId()).tenderId(done.getId())
                .outcome(LotOutcome.DONE).finalAt(OffsetDateTime.now()).build());
        Tender pending = ResultsFixtures.tender(tenders, 970000008L, NOW.minusDays(2), "10-ЗЦП1", "Итог ещё не окончательный");
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(pending.getLots().get(0).getId()).tenderId(pending.getId())
                .outcome(LotOutcome.PENDING).build());
        Tender priv = ResultsFixtures.tender(tenders, 970000007L, NOW.minusDays(2), "9-ЗЦП1", "Частная");
        priv.setSource(Source.PRIVATE_REQUEST);
        tenders.saveAndFlush(priv);
        List<Long> ids = tenders.findResultCandidates(NOW, NOW.minusDays(45), 500);
        assertThat(ids).contains(passed.getId(), pending.getId()).doesNotContain(future.getId(), old.getId(), done.getId(), priv.getId());
    }
}
