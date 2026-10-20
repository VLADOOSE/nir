package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.TenderController;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Отметки «наш товар» в API «Тендеров» (спека §12.2–12.3): сводка в списке, отметки лота со ссылкой на товар сайта. */
@SpringBootTest
@Transactional
@WithMockUser(roles = "USER")
class TenderOurProductApiTest {

    @Autowired TenderController controller;
    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void listSummaryAndLotMatches() {
        ProductGroup g = groupRepo.save(ProductGroup.builder().market(Market.KZ).name("Отсасыватели и аспирация дыма")
                .searchWords(List.of("отсасыватель")).position(99).build());
        SiteProduct p = productRepo.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("Аппарат для аспирации дыма АСД-\"ФОТЕК\". Набор").slug("asd-fotek").brand("Фотек")
                .syncedAt(OffsetDateTime.now()).build());
        SiteProduct similar = productRepo.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("Отсасыватель хирургический ОХ-10").slug("oh-10").brand("Аксион")
                .syncedAt(OffsetDateTime.now()).build());
        Tender t = new Tender();
        t.setTenderNumber("PW-API"); t.setSourceExtId("PW-API"); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setFoundByProductSearch(true);
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName("Отсасыватель");
        t.getLots().add(l);
        Tender other = new Tender();
        other.setTenderNumber("PW-API-2"); other.setSourceExtId("PW-API-2"); other.setStatus("ACTIVE"); other.setMarket(Market.KZ);
        tenders.save(t);
        tenders.save(other);
        em.flush();
        matches.save(TenderLotProductMatch.builder().tenderId(t.getId()).lotId(l.getId()).groupId(g.getId()).word("отсасыватель")
                .level(ProductMatchLevel.COPIED).evidence("совпало 5 фраз: блок аспирации").productId(p.getId())
                .similar(new java.util.ArrayList<>(List.of(new SimilarProductRef(similar.getId(), similar.getName(), "Аксион", "oh-10", 0.7))))
                .source(ProductMatchSource.SEARCH).build());
        em.flush(); em.clear();

        List<TenderResponse> all = controller.findAll();
        TenderResponse r = all.stream().filter(x -> x.getId().equals(t.getId())).findFirst().orElseThrow();
        assertThat(r.isFoundByProductSearch()).isTrue();
        assertThat(r.getOurProduct().level()).isEqualTo("COPIED");
        assertThat(r.getOurProduct().label()).isEqualTo("★ наш товар: Аппарат для аспирации дыма АСД-\"ФОТЕК\"");
        assertThat(r.getOurProduct().groups()).containsExactly("Отсасыватели и аспирация дыма");
        assertThat(all.stream().filter(x -> x.getId().equals(other.getId())).findFirst().orElseThrow().getOurProduct())
                .as("без отметок — пусто").isNull();

        TenderLotResponse lot = controller.getLots(t.getId()).get(0);
        assertThat(lot.getProductMatches()).hasSize(1);
        LotProductMatchDto m = lot.getProductMatches().get(0);
        assertThat(m.productUrl()).endsWith("/product/asd-fotek");
        assertThat(m.productName()).isEqualTo("Аппарат для аспирации дыма АСД-\"ФОТЕК\"");
        assertThat(m.similar()).extracting(LotProductMatchDto.Similar::url).containsExactly(m.productUrl().replace("asd-fotek", "oh-10"));
        assertThat(controller.findById(t.getId()).getOurProduct()).isNotNull();
        assertThat(controller.getLots(other.getId())).isEmpty();
    }
}
