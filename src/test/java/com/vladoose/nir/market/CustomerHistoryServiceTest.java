package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.CustomerHistory;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.integration.goszakup.dto.ClosedLot;
import com.vladoose.nir.integration.goszakup.dto.ContractRow;
import com.vladoose.nir.integration.goszakup.dto.ContractUnitRow;
import com.vladoose.nir.integration.goszakup.dto.SubjectInfo;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.market.CustomerHistoryService;
import com.vladoose.nir.service.market.MarketCollector;
import com.vladoose.nir.service.market.MarketContractWriter;
import com.vladoose.nir.service.tenderresults.GoszakupSubjects;
import com.vladoose.nir.tenderresults.FakeGoszakupResultsClient;
import com.vladoose.nir.tenderresults.ResultsFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** История заказчика (спека §8): договоры за 2 года — сначала той же категории, потом прочие медизделия; не медицина — мимо. */
@SpringBootTest
@Transactional
class CustomerHistoryServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);

    @Autowired MarketContractWriter writer;
    @Autowired MarketContractRepository contracts;
    @Autowired MarketLookupRepository lookups;
    @Autowired GoszakupSubjectRepository subjectRepo;
    @Autowired TenderRepository tenders;

    @BeforeEach
    void kz() {
        MarketContext.set(Market.KZ);
        lookups.deleteAllInBatch();
        contracts.deleteAllInBatch();   // nirdb постоянная: договоры этого заказчика из живых проверок — мимо (откат после)
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static ClosedLot lot(long id, String name, String code) {
        return new ClosedLot(id, id + "-ЗЦП1", name, null, BigDecimal.ONE, new BigDecimal("100000"), 99200000L + id % 1000,
                (99200000L + id % 1000) + "-1", "110240019426", "ГКП «ZZ больница»", code, null, "Штука", null, "Запрос ценовых предложений");
    }

    @Test
    void sameCategoryFirst_thenMedical_noOffice() {
        Tender t = ResultsFixtures.tender(tenders, 970007001L, NOW.minusDays(3), "1-ЗЦП1", "Тележка");
        t.getLots().get(0).setEnstruCode("309911.100.000001");
        tenders.saveAndFlush(t);
        FakeGoszakupResultsClient fake = new FakeGoszakupResultsClient();
        fake.contracts.add(new ContractRow(1L, 99200001L, "Д-1", "2026-09-01 10:00:00", 190, "900000000001", new BigDecimal("100000"),
                List.of(new ContractUnitRow(99300001L, new BigDecimal("100000"), BigDecimal.ONE, new BigDecimal("100000")))));
        fake.contracts.add(new ContractRow(2L, 99200002L, "Д-2", "2026-10-01 10:00:00", 190, "900000000002", new BigDecimal("200000"),
                List.of(new ContractUnitRow(99300002L, new BigDecimal("200000"), BigDecimal.ONE, new BigDecimal("200000")))));
        fake.contracts.add(new ContractRow(3L, 99200003L, "Д-3", "2026-10-05 10:00:00", 190, "900000000003", new BigDecimal("5000"),
                List.of(new ContractUnitRow(99300003L, new BigDecimal("5000"), BigDecimal.ONE, new BigDecimal("5000")))));
        fake.lotsById.put(99300001L, lot(99300001L, "Тележка медицинская", "309911.100.000001"));
        fake.lotsById.put(99300002L, lot(99300002L, "Электрокардиограф 12-канальный", "266012.100.000000"));
        fake.lotsById.put(99300003L, lot(99300003L, "Бумага офисная А4", "171812.000.000000"));
        fake.subjects.put("900000000001", new SubjectInfo("900000000001", "ТОО «Поставщик»", null, null));
        GoszakupSubjects subjects = new GoszakupSubjects(fake, subjectRepo, CLOCK, ms -> { });
        MarketCollector collector = new MarketCollector(fake, writer, subjects, List::of, CLOCK, ms -> { });
        CustomerHistory h = new CustomerHistoryService(collector, contracts, subjectRepo, CLOCK).forTender(t);
        assertThat(h.rows()).extracting(CustomerHistory.Row::name).containsExactly("Тележка медицинская", "Электрокардиограф 12-канальный");
        assertThat(h.rows().get(0).sameCategory()).isTrue();
        assertThat(h.rows().get(0).supplierName()).isEqualTo("ТОО «Поставщик»");
        assertThat(h.error()).isNull();
    }
}
