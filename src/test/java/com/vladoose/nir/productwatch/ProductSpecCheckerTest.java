package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.LotSpecParser;
import com.vladoose.nir.service.TechSpecLotParser;
import com.vladoose.nir.service.TechSpecStatusWriter;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ТЗ найденных лотов (спека §7.4): отметка попытки до разбора, сверка по ТЗ, терминальные исходы, остановка на аварии. */
@SpringBootTest
@Transactional
class ProductSpecCheckerTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired TechSpecStatusWriter statusWriter;
    @Autowired EntityManager em;
    ProductMatchService matchService;
    CatalogSnapshot snap;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        List<ProductGroup> groups = ProductFixtures.groups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        List<SiteProduct> products = ProductFixtures.catalog().stream().peek(p -> p.setId(null)).map(productRepo::save).toList();
        snap = CatalogSnapshot.build(groups, products);
        matchService = new ProductMatchService(tenders, matches, () -> snap);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private TenderLot suctionLot() {
        Tender t = new Tender();
        t.setTenderNumber("PW-SPEC"); t.setStatus("ACTIVE"); t.setMarket(Market.KZ); t.setDescription("Аппарат для аспирации дыма");
        t.setPlatform(TenderPlatform.GOSZAKUP); t.setSourceExtId("PW-SPEC");
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName("Отсасыватель"); l.setRequiredSpec("для аспирации жидкого содержимого");
        l.setTechSpecStatus(TechSpecStatus.PENDING);
        t.getLots().add(l);
        tenders.save(t);
        em.flush();
        matchService.evaluate(t.getId(), ProductMatchSource.SEARCH);
        return l;
    }

    private ProductSpecChecker checker(LotSpecParser parser) {
        return new ProductSpecChecker(matches, lots, statusWriter, parser, matchService, () -> true, ms -> { });
    }

    @Test
    void parsedSpec_upgradesToCopied_attemptMarkedBeforeParse() {
        TenderLot l = suctionLot();
        List<Integer> attemptsSeen = new ArrayList<>();
        int done = checker(lotId -> {
            em.flush(); em.clear();
            TenderLot fresh = lots.findById(lotId).orElseThrow();
            attemptsSeen.add(fresh.getTechSpecAttempts());
            fresh.setRequiredSpec(ProductFixtures.spec("17779323-1.pdf"));
            fresh.setTechSpecStatus(TechSpecStatus.OK);
            lots.save(fresh);
            em.flush();
            return new TechSpecLotParser.Result(TechSpecStatus.OK, false);
        }).run(10, new ImportSummary());
        assertThat(done).isEqualTo(1);
        assertThat(attemptsSeen).containsExactly(1);
        TenderLotProductMatch m = matches.findByLotId(l.getId()).get(0);
        assertThat(m.getLevel()).isEqualTo(ProductMatchLevel.COPIED);
        assertThat(m.getSpecCheckedAt()).isNotNull();
    }

    @Test
    void noFile_checkedKeepsType() {
        TenderLot l = suctionLot();
        checker(id -> { statusWriter.markResult(id, TechSpecStatus.NO_FILE); return new TechSpecLotParser.Result(TechSpecStatus.NO_FILE, false); })
                .run(10, new ImportSummary());
        TenderLotProductMatch m = matches.findByLotId(l.getId()).get(0);
        assertThat(m.getLevel()).isEqualTo(ProductMatchLevel.TYPE);
        assertThat(m.getSpecCheckedAt()).isNotNull();
    }

    @Test
    void errorThreeTimes_thenChecked() {
        TenderLot l = suctionLot();
        ProductSpecChecker c = checker(id -> new TechSpecLotParser.Result(TechSpecStatus.ERROR, false));
        c.run(10, new ImportSummary());
        c.run(10, new ImportSummary());
        assertThat(matches.findByLotId(l.getId()).get(0).getSpecCheckedAt()).isNull();
        c.run(10, new ImportSummary());
        assertThat(matches.findByLotId(l.getId()).get(0).getSpecCheckedAt()).isNotNull();
        assertThat(lots.findById(l.getId()).orElseThrow().getTechSpecAttempts()).isEqualTo(3);
        assertThat(c.run(10, new ImportSummary())).as("больше не берётся").isZero();
    }

    @Test
    void alreadyParsed_noDownload() {
        TenderLot l = suctionLot();
        l.setTechSpecStatus(TechSpecStatus.OK);
        l.setRequiredSpec(ProductFixtures.spec("17779323-1.pdf"));
        lots.save(l);
        em.flush();
        int[] calls = {0};
        checker(id -> { calls[0]++; return new TechSpecLotParser.Result(TechSpecStatus.OK, false); }).run(10, new ImportSummary());
        assertThat(calls[0]).isZero();
        assertThat(matches.findByLotId(l.getId()).get(0).getLevel()).isEqualTo(ProductMatchLevel.COPIED);
    }
}
