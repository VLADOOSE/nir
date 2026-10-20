package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ProductMatchServiceTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired EntityManager em;
    ProductMatchService service;
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
        service = new ProductMatchService(tenders, matches, () -> snap);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private Tender tender(String announcement, String lotName, String descr) {
        Tender t = new Tender();
        t.setTenderNumber("PW-1"); t.setStatus("ACTIVE"); t.setMarket(Market.KZ); t.setDescription(announcement);
        t.setPlatform(TenderPlatform.GOSZAKUP); t.setSourceExtId("PW-1");
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName(lotName); l.setRequiredSpec(descr);
        t.getLots().add(l);
        tenders.save(t);
        em.flush();
        return t;
    }

    @Test
    void createUpgradeWithSpecAndRemoveStale() {
        Tender t = tender("Аппарат для аспирации дыма", "Отсасыватель", "для аспирации жидкого содержимого");
        ProductMatchService.Result r1 = service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        assertThat(r1.level()).isEqualTo(ProductMatchLevel.TYPE);
        assertThat(r1.hadMarks()).isFalse();

        TenderLot lot = t.getLots().get(0);
        lot.setTechSpecStatus(TechSpecStatus.OK);
        lot.setRequiredSpec(ProductFixtures.spec("17779323-1.pdf"));
        em.flush();
        ProductMatchService.Result r2 = service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        assertThat(r2.level()).isEqualTo(ProductMatchLevel.COPIED);
        assertThat(r2.hadMarks()).isTrue();
        TenderLotProductMatch m = matches.findByTenderId(t.getId()).get(0);
        assertThat(m.getSpecCheckedAt()).isNotNull();
        assertThat(m.getSource()).as("источник — первой отметки").isEqualTo(ProductMatchSource.SEARCH);

        lot.setEquipName("Бинт марлевый"); lot.setTechSpecStatus(null); lot.setRequiredSpec(null);
        t.setDescription("Перевязочные материалы");
        em.flush();
        assertThat(service.evaluate(t.getId(), ProductMatchSource.REMATCH).level()).isNull();
        assertThat(matches.findByTenderId(t.getId())).isEmpty();
    }

    /**
     * Живая проверка 2026-10-20: «Аспиратор сильфонный (ВКФ)» (пробоотборник газов) отмечался «наш вид»: после разбора ТЗ
     * описание лота пропадало из текста лота, и исключению «сильфон» не по чему было сработать.
     */
    @Test
    void parsedSpec_lotDescriptionFromHeader_exclusionWorks() {
        Tender t = tender("Аспиратор сильфонный (ВКФ)", "Аспиратор", null);
        TenderLot lot = t.getLots().get(0);
        lot.setTechSpecStatus(TechSpecStatus.OK);
        lot.setRequiredSpec("Номер закупки: № 17823512-1 Наименование лота: Аспиратор Описание лота: для газов, неавтоматический "
                + "Дополнительное описание лота: Аспиратор сильфонный АМ-0059 (ВКФ) Количество: 1 "
                + "характеристики закупаемых товаров: корпус металлический, объём 100 мл");
        em.flush();
        assertThat(service.evaluate(t.getId(), ProductMatchSource.SEARCH).level()).isNull();
    }

    @Test
    void markNotified() {
        Tender t = tender("Аппарат для аспирации дыма", "Отсасыватель", "");
        service.markNotified(t.getId(), ProductMatchLevel.TYPE);
        em.flush(); em.clear();
        assertThat(tenders.findById(t.getId()).orElseThrow().getProductNotifiedLevel()).isEqualTo(ProductMatchLevel.TYPE);
    }
}
