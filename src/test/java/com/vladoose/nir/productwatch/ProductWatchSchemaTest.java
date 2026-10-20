package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V36 (спека 2026-10-19-product-watch §4): сид групп, JSONB туда и обратно, каскады. */
@SpringBootTest
@Transactional
class ProductWatchSchemaTest {

    @Autowired ProductGroupRepository groups;
    @Autowired SiteProductRepository products;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProcurementPlanPointRepository planPoints;
    @Autowired ProcurementPlanMatchRepository planMatches;
    @Autowired TenderRepository tenders;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    /** Сид V36 — пока группы KZ не правили (updated_at пуст у всех); правили — только инварианты. */
    @Test
    void seed_24kzGroups_brandGroupFirst() {
        List<ProductGroup> kz = groups.findByMarketOrderByPositionAscIdAsc(Market.KZ);
        assertThat(kz).allSatisfy(g -> assertThat(g.getSearchWords()).isNotEmpty());
        if (kz.stream().anyMatch(g -> g.getUpdatedAt() != null)) return;
        assertThat(kz).hasSize(24);
        assertThat(kz.get(0).isBrandGroup()).isTrue();
        assertThat(kz.get(0).getSearchWords()).contains("фотек", "аксион", "эмалед", "эхвч");
        assertThat(kz).filteredOn(ProductGroup::isBrandGroup).hasSize(1);
        ProductGroup lamps = kz.stream().filter(g -> g.getName().startsWith("Светильники")).findFirst().orElseThrow();
        assertThat(lamps.getSearchWords()).containsExactly("светильник");
        assertThat(lamps.getRequireWords()).contains("операцион", "хирург");
        assertThat(lamps.getExcludeWords()).contains("уличн", "прожектор");
        assertThat(lamps.getBrandWords()).contains("эмалед");
        assertThat(groups.findByMarketOrderByPositionAscIdAsc(Market.RF)).isEmpty();
    }

    @Test
    void jsonbRoundTripAndCascades() {
        ProductGroup g = groups.save(ProductGroup.builder().market(Market.KZ).name("Тест-группа")
                .searchWords(List.of("тестслово")).position(99).build());
        SiteProduct p = products.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("Тестовый аппарат ТА-100").brand("Фотек")
                .attributes(List.of(new SiteProductAttribute("Мощность", "100 Вт")))
                .syncedAt(OffsetDateTime.now()).build());
        Tender t = new Tender();
        t.setTenderNumber("PW-TEST-1"); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        TenderLot lot = new TenderLot(); lot.setTender(t); lot.setEquipName("Тестовый аппарат");
        t.getLots().add(lot);
        tenders.save(t);
        em.flush();
        matches.save(TenderLotProductMatch.builder().tenderId(t.getId()).lotId(lot.getId()).groupId(g.getId())
                .word("тестслово").level(ProductMatchLevel.COPIED).evidence("совпало 3 фразы").productId(p.getId())
                .similar(List.of(new SimilarProductRef(p.getId(), p.getName(), "Фотек", "ta-100", 0.8)))
                .source(ProductMatchSource.SEARCH).build());
        em.flush(); em.clear();

        TenderLotProductMatch m = matches.findByTenderId(t.getId()).get(0);
        assertThat(m.getSimilar()).extracting(SimilarProductRef::slug).containsExactly("ta-100");
        assertThat(products.findBySiteId(p.getSiteId()).orElseThrow().getAttributes())
                .extracting(SiteProductAttribute::value).containsExactly("100 Вт");
        assertThat(ProductMatchLevel.max(ProductMatchLevel.TYPE, ProductMatchLevel.COPIED)).isEqualTo(ProductMatchLevel.COPIED);
        assertThat(ProductMatchLevel.NAMED.exact()).isTrue();
        assertThat(ProductMatchLevel.TYPE.exact()).isFalse();

        tenders.deleteById(t.getId());                       // каскад тендер → лот → отметка
        em.flush(); em.clear();
        assertThat(matches.findByTenderId(t.getId())).isEmpty();

        ProcurementPlanPoint pp = planPoints.save(ProcurementPlanPoint.builder().market(Market.KZ).extId(-1L).year(2026)
                .name("Электрокардиограф").level(ProductMatchLevel.TYPE)
                .firstSeenAt(OffsetDateTime.now()).lastSeenAt(OffsetDateTime.now()).build());
        planMatches.save(ProcurementPlanMatch.builder().planPointId(pp.getId()).groupId(g.getId()).word("тестслово").build());
        em.flush(); em.clear();
        groups.deleteById(g.getId());                        // каскад группа → отметки плана
        em.flush(); em.clear();
        assertThat(planMatches.findByPlanPointIdIn(List.of(pp.getId()))).isEmpty();
    }
}
