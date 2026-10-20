package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.SourceRemovedLots;
import com.vladoose.nir.integration.goszakup.*;
import com.vladoose.nir.integration.goszakup.dto.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
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

/** Поиск по стране (спека §7): кандидаты, заведение, отметки, остановка на 403, обновление пропавших. */
@SpringBootTest
@Transactional
class ProductWatchServiceTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired RegionResolver regionResolver;
    @Autowired SourceRemovedLots removedLots;
    @Autowired EntityManager em;

    /** 2026-10-19 10:00 по Уральску. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC);
    FakeGoszakupSearchClient search;
    FakeGoszakupClient client;
    ProductWatchService service;
    CatalogSnapshot snap;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        List<ProductGroup> groups = ProductFixtures.groups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        List<SiteProduct> products = ProductFixtures.catalog().stream().peek(p -> p.setId(null)).map(productRepo::save).toList();
        em.flush();
        snap = CatalogSnapshot.build(groups, products);
        search = new FakeGoszakupSearchClient();
        client = new FakeGoszakupClient();
        GoszakupTenderWriter writer = new GoszakupTenderWriter(tenders, regionResolver, removedLots, CLOCK);
        ProductMatchService matchService = new ProductMatchService(tenders, matches, () -> snap);
        service = new ProductWatchService(search, client, writer, matchService, () -> snap, tenders, CLOCK, ms -> { });
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static FoundAnnouncement astana(String endDate) {
        return new FoundAnnouncement(17802320L, "17802320-1", "Аппарат для аспирации дыма", "080740007819",
                "ГКП на ПХВ «Городская многопрофильная больница № 2» акимата города Астана", new BigDecimal("3714285.71"),
                "2026-10-16 19:32:08", endDate, 220, 6, "Из одного источника по несостоявшимся закупкам", 3);
    }

    static FoundLot lot(FoundAnnouncement a, String name, String descr) {
        return new FoundLot(1L, "88111123-ОИ3", name, descr, BigDecimal.ONE, new BigDecimal("3714285.71"), 220,
                "2026-10-16 19:32:08", a.orgBin(), a.orgNameRu(), a.numberAnno(), a);
    }

    private void astanaOnPlatform() {
        FoundAnnouncement a = astana("2026-10-20 09:00:08");
        search.lotsByWord.put("отсасыватель", List.of(lot(a, "Отсасыватель", "для аспирации жидкого содержимого")));
        LotDto l = new LotDto();
        l.setLotNumber("88111123-ОИ3"); l.setNameRu("Отсасыватель"); l.setDescriptionRu("для аспирации жидкого содержимого");
        l.setCount(BigDecimal.ONE); l.setAmount(new BigDecimal("3714285.71"));
        client.lotsByAnno.put("17802320-1", List.of(l));
        SubjectDto s = new SubjectDto();
        s.setNameRu("ГКП на ПХВ «Городская многопрофильная больница № 2» акимата города Астана");
        SubjectAddressDto addr = new SubjectAddressDto();
        addr.setAddress("г. Астана, ул. Ш. Айманова 23");
        s.setAddress(List.of(addr));
        client.subjectsByBin.put("080740007819", s);
    }

    @Test
    void newAnnouncement_importedWithMarksRegionMethod() {
        astanaOnPlatform();
        ImportSummary sum = new ImportSummary();
        service.run(sum);
        Tender t = tenders.findBySourceExtId("17802320-1").orElseThrow();
        assertThat(t.isFoundByProductSearch()).isTrue();
        assertThat(t.getRegion()).isEqualTo("г. Астана");
        assertThat(t.getPurchaseType()).isEqualTo("Из одного источника по несостоявшимся закупкам");   // 46 символов — влезает в 50
        assertThat(t.getDeadlineAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0, 8));
        List<TenderLotProductMatch> m = matches.findByTenderId(t.getId());
        assertThat(m).hasSize(1);
        assertThat(m.get(0).getLevel()).isEqualTo(ProductMatchLevel.TYPE);
        assertThat(m.get(0).getSource()).isEqualTo(ProductMatchSource.SEARCH);
        assertThat(sum.getCreated()).isEqualTo(1);
        assertThat(sum.getErrors()).isZero();
    }

    @Test
    void sameAnnouncementSeveralWords_importedOnce() {   // Review Focus #1
        astanaOnPlatform();
        search.buysByWord.put("аспирации", List.of(astana("2026-10-20 09:00:08")));
        ImportSummary sum = new ImportSummary();
        service.run(sum);
        assertThat(sum.getCreated()).isEqualTo(1);
        assertThat(client.lotsCalls.get("17802320-1")).isEqualTo(1);
        assertThat(tenders.findBySourceExtId("17802320-1")).isPresent();
    }

    @Test
    void noOurLot_notImported() {
        // название закупки нейтральное: у объявления с одним лотом название тоже проходит группу (спека §5.2)
        FoundAnnouncement a = new FoundAnnouncement(17802320L, "17802320-1", "Приобретение оргтехники", "080740007819",
                "ГКП «Городская больница»", new BigDecimal("300000"), "2026-10-16 19:32:08", "2026-10-20 09:00:08", 220, 3,
                "Запрос ценовых предложений", 3);
        search.lotsByWord.put("монитор", List.of(lot(a, "Монитор компьютерный", "диагональ 24")));
        LotDto l = new LotDto();
        l.setLotNumber("1"); l.setNameRu("Монитор компьютерный"); l.setDescriptionRu("диагональ 24");
        client.lotsByAnno.put("17802320-1", List.of(l));
        ImportSummary sum = new ImportSummary();
        service.run(sum);
        assertThat(tenders.findBySourceExtId("17802320-1")).isEmpty();
        assertThat(sum.getSkipped()).isEqualTo(1);
    }

    @Test
    void deadlinePassed_notCandidate() {
        FoundAnnouncement a = astana("2026-10-19 09:00:00");   // час назад по Уральску
        search.lotsByWord.put("отсасыватель", List.of(lot(a, "Отсасыватель", "")));
        service.run(new ImportSummary());
        assertThat(client.lotsCalls).isEmpty();
    }

    @Test
    void forbiddenMidRun_stopsKeepsWritten() {   // Review Focus #5
        astanaOnPlatform();
        FoundAnnouncement b = new FoundAnnouncement(1L, "17000001-1", "Облучатель бактерицидный", "111", "Школа", BigDecimal.TEN,
                "2026-10-18 10:00:00", "2026-10-21 10:00:00", 220, 2, "Запрос ценовых предложений", 3);
        FoundAnnouncement c = new FoundAnnouncement(2L, "17000002-1", "Облучатель бактерицидный", "222", "Детсад", BigDecimal.TEN,
                "2026-10-18 10:00:00", "2026-10-22 10:00:00", 220, 2, "Запрос ценовых предложений", 3);
        search.buysByWord.put("облучатель", List.of(b, c));
        // объявление Астаны обрабатывается первым (срок раньше), второе — 403 на лотах, третье уже не запрашивается
        client.forbiddenLots.add("17000001-1");
        ImportSummary sum = new ImportSummary();
        service.run(sum);
        assertThat(tenders.findBySourceExtId("17802320-1")).isPresent();
        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getLastError()).contains("403");
        assertThat(client.lotsCalls).doesNotContainKey("17000002-1");
    }

    @Test
    void vanishedFoundTender_refreshedByNumber() {
        astanaOnPlatform();
        service.run(new ImportSummary());
        search.lotsByWord.clear();
        search.byNumber.put("17802320-1", new FoundAnnouncement(17802320L, "17802320-1", "Аппарат для аспирации дыма", "080740007819",
                null, null, null, "2026-10-20 09:00:08", 430, 6, null, 3));
        service.run(new ImportSummary());
        assertThat(tenders.findBySourceExtId("17802320-1").orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void notConfigured_disabled() {
        search.configured = false;
        ImportSummary sum = new ImportSummary();
        service.run(sum);
        assertThat(sum.isEnabled()).isFalse();
    }
}
