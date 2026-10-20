package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.AnnouncementResult;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.FakeGoszakupClient;
import com.vladoose.nir.integration.goszakup.GoszakupTenderWriter;
import com.vladoose.nir.integration.goszakup.dto.*;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.productwatch.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AnnouncementServiceTest {

    @Autowired GoszakupTenderWriter writer;
    @Autowired ProductMatchService matchService;
    @Autowired TenderRepository tenders;
    FakeGoszakupSearchClient search;
    FakeGoszakupClient client;
    AnnouncementService announcements;
    ProductWatchService watch;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        search = new FakeGoszakupSearchClient();
        client = new FakeGoszakupClient();
        announcements = new AnnouncementService(search, client, ms -> { });
        CatalogSnapshot snap = CatalogSnapshot.build(ProductFixtures.groups(), List.of());
        watch = new ProductWatchService(search, client, writer, matchService, () -> snap, tenders,
                Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC), ms -> { });
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void result_contractWithSupplierName() {
        search.contracts.put("17700001-1", List.of(new ContractInfo("150540012345", new BigDecimal("3260000"),
                new BigDecimal("3650000"), "2026-09-12 15:20:00", 2)));
        SubjectDto s = new SubjectDto();
        s.setBin("150540012345");
        s.setNameRu("ТОО «Медтехника-КЗ»");
        client.subjectsByBin.put("150540012345", s);
        AnnouncementResult r = announcements.result("17700001-1");
        assertThat(r.supplierName()).isEqualTo("ТОО «Медтехника-КЗ»");
        assertThat(r.contractSum()).isEqualByComparingTo("3650000");
        assertThat(r.signDate()).isEqualTo("2026-09-12");
        assertThat(r.status()).isEqualTo("договор есть");
    }

    @Test
    void result_noContractYet() {
        assertThat(announcements.result("17700002-1").status()).isEqualTo("договор ещё не опубликован");
    }

    @Test
    void importAnnouncement_withoutOurLot_createdOnce() {
        FoundAnnouncement a = new FoundAnnouncement(1L, "17700003-1", "Приобретение мебели", "990240006109", "КГП «Больница»",
                new BigDecimal("500000"), "2026-10-10 10:00:00", "2026-10-25 10:00:00", 220, 3, "Запрос ценовых предложений", 3);
        search.byNumber.put("17700003-1", a);
        LotDto l = new LotDto();
        l.setLotNumber("1");
        l.setNameRu("Стол письменный");
        l.setCount(BigDecimal.ONE);
        l.setAmount(new BigDecimal("500000"));
        client.lotsByAnno.put("17700003-1", List.of(l));
        long id = watch.importAnnouncement("17700003-1");
        Tender t = tenders.findById(id).orElseThrow();
        assertThat(t.isFoundByProductSearch()).isTrue();
        assertThat(t.getLots()).hasSize(1);
        assertThat(watch.importAnnouncement("17700003-1")).isEqualTo(id);
        assertThat(client.lotsCalls.get("17700003-1")).isEqualTo(1);
    }
}
