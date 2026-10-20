package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Отметки у импорта ЗКО / СК-Фармации и тихий пересчёт (спека §8). */
@SpringBootTest
@Transactional
class ProductRematchServiceTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
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

    private Tender tender(String ext, String lotName) {
        Tender t = new Tender();
        t.setTenderNumber(ext); t.setSourceExtId(ext); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setDescription("Закуп медицинских изделий"); t.setPlatform(TenderPlatform.GOSZAKUP);
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName(lotName);
        t.getLots().add(l);
        tenders.save(t);
        em.flush();
        return t;
    }

    @Test
    void importMarks_touchedOnly_sourceImport() {
        Tender ours = tender("PW-IMP-1", "Облучатель бактерицидный настенный");
        tender("PW-IMP-2", "Облучатель бактерицидный передвижной");     // не затронут прогоном
        ImportSummary sum = new ImportSummary();
        sum.getTouchedExtIds().add("PW-IMP-1");
        new ProductImportMarks(matchService, tenders, true).afterImport(sum);
        assertThat(matches.findByTenderId(ours.getId())).extracting(TenderLotProductMatch::getSource).containsExactly(ProductMatchSource.IMPORT);
        assertThat(matches.findByTenderId(tenders.findBySourceExtId("PW-IMP-2").orElseThrow().getId())).isEmpty();
        ImportSummary off = new ImportSummary();
        off.getTouchedExtIds().add("PW-IMP-2");
        new ProductImportMarks(matchService, tenders, false).afterImport(off);
        assertThat(matches.findByTenderId(tenders.findBySourceExtId("PW-IMP-2").orElseThrow().getId())).isEmpty();
    }

    @Test
    void rematch_silentForNew_keepsPendingForEarlierMarks() {
        Tender fresh = tender("PW-RM-1", "Дефибриллятор");                   // группы «Дефибрилляторы» в фикстуре нет → без отметки
        Tender fresh2 = tender("PW-RM-2", "Рециркулятор бактерицидный");
        Tender pending = tender("PW-RM-3", "Облучатель бактерицидный");
        matchService.evaluate(pending.getId(), ProductMatchSource.SEARCH);  // отметили поиском, сообщение ещё не ушло
        int n = new ProductRematchService(tenders, matchService, () -> { }).rematchOpen();
        em.flush(); em.clear();
        assertThat(n).isGreaterThanOrEqualTo(2);
        assertThat(tenders.findById(fresh2.getId()).orElseThrow().getProductNotifiedLevel()).as("молча").isEqualTo(ProductMatchLevel.TYPE);
        assertThat(tenders.findById(pending.getId()).orElseThrow().getProductNotifiedLevel()).as("ждёт сообщения").isNull();
        assertThat(tenders.findById(fresh.getId()).orElseThrow().getProductNotifiedLevel()).isNull();
    }
}
