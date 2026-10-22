package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MarketContract;
import com.vladoose.nir.entity.MarketLookup;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.dto.ClosedLot;
import com.vladoose.nir.integration.goszakup.dto.ContractRow;
import com.vladoose.nir.integration.goszakup.dto.ContractUnitRow;
import com.vladoose.nir.repository.GoszakupSubjectRepository;
import com.vladoose.nir.repository.MarketContractRepository;
import com.vladoose.nir.repository.MarketLookupRepository;
import com.vladoose.nir.service.market.MarketCollector;
import com.vladoose.nir.service.market.MarketContractWriter;
import com.vladoose.nir.service.tenderresults.GoszakupSubjects;
import com.vladoose.nir.tenderresults.FakeGoszakupResultsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Набор цен рынка (спека 2026-10-22-tender-results-market-prices §9). */
@SpringBootTest
@Transactional
class MarketCollectorTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);
    static final String CODE = "266011.150.000000";

    @Autowired MarketContractWriter writer;
    @Autowired MarketContractRepository contracts;
    @Autowired MarketLookupRepository lookups;
    @Autowired GoszakupSubjectRepository subjectRepo;

    FakeGoszakupResultsClient fake;
    MarketCollector collector;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        lookups.deleteAllInBatch();
        contracts.deleteAllInBatch();
        fake = new FakeGoszakupResultsClient();
        collector = new MarketCollector(fake, writer, new GoszakupSubjects(fake, subjectRepo, CLOCK, ms -> { }),
                () -> List.of("дефибриллятор"), CLOCK, ms -> { });
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static ClosedLot lot(long id, long buy, String count, String amount) {
        return new ClosedLot(id, id + "-ЗЦП1", "Дефибриллятор", "для терапии нарушений сердечного ритма",
                count == null ? null : new BigDecimal(count), new BigDecimal(amount), buy, buy + "-1", "990240006109", "ГКП «Больница»",
                CODE, "бифазный, с монитором", "Штука", "Актюбинская область, г.Актобе", "Запрос ценовых предложений");
    }

    static ContractRow contract(long id, long buy, long lotId, String price, String signed, int status) {
        return new ContractRow(id, buy, "Д-" + id, signed, status, "900000000001", new BigDecimal(price),
                List.of(new ContractUnitRow(lotId, new BigDecimal(price), BigDecimal.ONE, new BigDecimal(price))));
    }

    @Test
    void daily_rowFromLotAndLatestConcludedContract() {
        fake.closed.put("дефибриллятор", List.of(lot(99000001L, 99100001L, "2", "2800000"), lot(99000002L, 99100002L, "1", "900000")));
        fake.contracts.add(contract(1, 99100001L, 99000001L, "1340000", "2026-05-10 10:00:00", 190));
        fake.contracts.add(contract(2, 99100001L, 99000001L, "1", "2026-06-01 10:00:00", 330));   // не заключён — мимо
        ImportSummary sum = new ImportSummary();
        collector.collectDaily(sum);
        assertThat(sum.getErrors()).isZero();
        List<MarketContract> rows = contracts.findByEnstruCodeAndSignedAtGreaterThanEqual(CODE, LocalDate.of(2024, 10, 22));
        assertThat(rows).hasSize(1);
        MarketContract m = rows.get(0);
        assertThat(m.getUnitPrice()).isEqualByComparingTo("1340000");
        assertThat(m.getBudgetUnitPrice()).isEqualByComparingTo("1400000.00");
        assertThat(m.getRegion()).isEqualTo("Актюбинская область");
        assertThat(m.getSignedAt()).isEqualTo(LocalDate.of(2026, 5, 10));
        assertThat(m.getUnit()).isEqualTo("Штука");
        assertThat(fake.calls).contains("closed:дефибриллятор@2024-10-22");
        assertThat(lookups.findById(MarketCollector.DAILY)).isPresent();
        collector.collectDaily(new ImportSummary());
        assertThat(contracts.findByLotExtIdIn(List.of(99000001L))).as("повторный сбор не дублирует").hasSize(1);
    }

    /** Дальше — с прошлого удачного сбора минус 3 дня. */
    @Test
    void daily_fromLastSuccessMinus3Days() {
        lookups.saveAndFlush(MarketLookup.builder().lookupKey(MarketCollector.DAILY).fetchedAt(OffsetDateTime.parse("2026-10-20T03:00:00Z")).build());
        collector.collectDaily(new ImportSummary());
        assertThat(fake.calls).contains("closed:дефибриллятор@2026-10-17");
    }

    /** Без количества — бюджета за единицу нет, строка есть (Review Focus 4). */
    @Test
    void noQuantity_noBudget() {
        fake.closed.put("дефибриллятор", List.of(lot(99000003L, 99100003L, null, "2800000")));
        fake.contracts.add(contract(3, 99100003L, 99000003L, "1340000", "2026-05-10 10:00:00", 190));
        collector.collectDaily(new ImportSummary());
        MarketContract m = contracts.findByLotExtIdIn(List.of(99000003L)).get(0);
        assertThat(m.getBudgetUnitPrice()).isNull();
        assertThat(m.getUnitPrice()).isEqualByComparingTo("1340000");
    }

    /** 403 — стоп, «удачный сбор» не отмечается. */
    @Test
    void stopCode_stops_notMarked() {
        fake.forbidFromCall = 1;
        ImportSummary sum = new ImportSummary();
        collector.collectDaily(sum);
        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getLastError()).contains("сбор остановлен");
        assertThat(lookups.findById(MarketCollector.DAILY)).isEmpty();
    }

    /** Живой запрос: свежий — без площадки; сбой — повтор не раньше чем через 10 мин. */
    @Test
    void ensureWord_freshNoCall_errorBackoff() {
        MarketCollector.Lookup first = collector.ensureWord("Дефибриллятор", Duration.ofSeconds(20));
        assertThat(first.ok()).isTrue();
        int calls = fake.calls.size();
        assertThat(collector.ensureWord("дефибриллятор", Duration.ofSeconds(20)).ok()).isTrue();
        assertThat(fake.calls).hasSize(calls);
        fake.forbidFromCall = fake.calls.size() + 1;
        assertThat(collector.ensureWord("монитор", Duration.ofSeconds(20)).ok()).isFalse();
        int afterFail = fake.calls.size();
        assertThat(collector.ensureWord("монитор", Duration.ofSeconds(20)).error()).contains("не удалось обновить");
        assertThat(fake.calls).as("в течение 10 мин после сбоя площадку не дёргаем").hasSize(afterFail);
    }
}
