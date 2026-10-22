package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.market.MarketCollector;
import com.vladoose.nir.service.market.MarketContractWriter;
import com.vladoose.nir.service.market.MarketPriceService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** «рынок: медиана … (N дог.) · бюджет …» для сообщений (спека 2026-10-22-tender-results-market-prices §10). */
@SpringBootTest
@Transactional
class MarketTelegramLinesTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);
    static final String CODE = "266011.150.000000";

    @Autowired MarketContractRepository contracts;
    @Autowired MarketLookupRepository lookups;
    @Autowired MarketContractWriter writer;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotBidRepository bids;
    @Autowired GoszakupSubjectRepository subjectRepo;
    @Autowired TenderRepository tenders;

    FakeGoszakupResultsClient fake;
    MarketPriceService service;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        contracts.deleteAllInBatch();
        lookups.deleteAllInBatch();
        fake = new FakeGoszakupResultsClient();
        MarketCollector collector = new MarketCollector(fake, writer, new GoszakupSubjects(fake, subjectRepo, CLOCK, ms -> { }),
                List::of, CLOCK, ms -> { });
        service = new MarketPriceService(collector, writer, contracts, lots, bids, subjectRepo, fake, CLOCK);
        for (int i = 0; i < 2; i++) {
            contracts.saveAndFlush(MarketContract.builder().lotExtId(99600000L + i).enstruCode(CODE).name("Дефибриллятор").unit("Штука")
                    .unitPrice(new BigDecimal("1340000")).signedAt(LocalDate.of(2026, 5, 10)).build());
        }
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void lineForLotWithData_noneWithout_liveAskedOnce() {
        Tender t = ResultsFixtures.tender(tenders, 970010001L, NOW.plusDays(3), "1-ЗЦП1", "Дефибриллятор бифазный", "2-ЗЦП1", "Кушетка");
        TenderLot defib = t.getLots().get(0);
        defib.setEnstruCode(CODE);
        defib.setPlanUnit("Штука");
        defib.setMaxCost(new BigDecimal("1420000"));
        tenders.saveAndFlush(t);
        Tender other = ResultsFixtures.tender(tenders, 970010002L, NOW.plusDays(3), "1-ЗЦП1", "Кушетка смотровая");
        Map<Long, List<Long>> in = new LinkedHashMap<>();
        in.put(t.getId(), List.of(t.getLots().get(1).getId(), defib.getId()));   // у первого лота данных нет — берётся следующий
        in.put(other.getId(), List.of(other.getLots().get(0).getId()));
        Map<Long, String> out = service.telegramLines(in, Duration.ofSeconds(30));
        assertThat(out.get(t.getId())).isEqualTo("рынок: медиана 1,34\u00A0млн\u00A0₸ (2 дог.) · бюджет 1,42\u00A0млн\u00A0₸");
        assertThat(out).doesNotContainKey(other.getId());
        assertThat(fake.calls.stream().filter(c -> c.startsWith("closed:")).toList())
                .as("живой запрос — по разу на слово").containsExactlyInAnyOrder("closed:кушетка@2024-10-22", "closed:дефибриллятор@2024-10-22");
    }

    /** Время вышло — без живых запросов, строки — из набора. */
    @Test
    void noTimeLeft_dbOnly() {
        Tender t = ResultsFixtures.tender(tenders, 970010003L, NOW.plusDays(3), "1-ЗЦП1", "Дефибриллятор бифазный");
        TenderLot l = t.getLots().get(0);
        l.setEnstruCode(CODE);
        tenders.saveAndFlush(t);
        Map<Long, String> out = service.telegramLines(Map.of(t.getId(), List.of(l.getId())), Duration.ZERO);
        assertThat(out.get(t.getId())).startsWith("рынок: медиана 1,34");
        assertThat(fake.calls).isEmpty();
    }
}
