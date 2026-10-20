package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.RegionResolver;
import com.vladoose.nir.integration.goszakup.dto.FoundPlanPoint;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Планы закупок (спека §10): только «Утверждён», услуги — мимо, регион по месту поставки, «объявлен / снят». */
@SpringBootTest
@Transactional
class ProcurementPlanServiceTest {

    @Autowired ProcurementPlanPointRepository points;
    @Autowired ProcurementPlanMatchRepository planMatches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired ProcurementPlanWriter writer;
    @Autowired RegionResolver regions;
    @Autowired EntityManager em;
    FakeGoszakupSearchClient search;
    ProcurementPlanService service;

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        points.deleteAllInBatch();
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        List<ProductGroup> gs = ProductFixtures.groups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        CatalogSnapshot snap = CatalogSnapshot.build(gs, List.of());
        search = new FakeGoszakupSearchClient();
        service = new ProcurementPlanService(search, writer, () -> snap, regions, CLOCK, ms -> { });
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static FoundPlanPoint plan(long id, String name, String desc, String unit, String place) {
        return new FoundPlanPoint(id, 2026, 11, "Ноябрь", "990240006109", "ГКП «Айтекебийская районная больница»", name, desc, "",
                new BigDecimal("4"), unit, new BigDecimal("1480000"), new BigDecimal("5920000"), "Электронный магазин", 2, "Утвержден", place);
    }

    @Test
    void collect_onlyOurs_noServices_region_firstRun() {
        search.plansByWord.put("рециркулятор", List.of(
                plan(1, "Рециркулятор", "бактерицидный на 2 лампы", "Штука", "Актюбинская область, Айтекебийский район"),
                plan(2, "Рециркулятор", "ремонт", "Одна услуга", "г.Алматы")));
        search.plansByWord.put("светильник", List.of(plan(3, "Светильник", "уличный светодиодный", "Штука", "г.Алматы")));
        ImportSummary sum = new ImportSummary();
        assertThat(service.collect(sum)).as("первый сбор").isTrue();
        List<ProcurementPlanPoint> all = points.findAll();
        assertThat(all).extracting(ProcurementPlanPoint::getExtId).containsExactly(1L);
        assertThat(all.get(0).getRegion()).isEqualTo("Актюбинская область");
        assertThat(all.get(0).getMonth()).isEqualTo(11);
        assertThat(planMatches.findByPlanPointIdIn(List.of(all.get(0).getId()))).hasSize(1);
        assertThat(service.collect(new ImportSummary())).as("второй — не первый").isFalse();
    }

    /** Модель из «Наших брендов и моделей» группы в тексте пункта — ★ (NAMED); одно слово группы на пункт — одна отметка. */
    @Test
    void namedModel_starLevel_repeatCollectKeepsOneMatch() {
        search.plansByWord.put("электрокардиограф", List.of(plan(7, "Электрокардиограф", "ЭК12Т-01-«Р-Д» 12-канальный", "Штука", "г.Алматы")));
        service.collect(new ImportSummary());
        service.collect(new ImportSummary());
        ProcurementPlanPoint p = points.findByExtId(7L).orElseThrow();
        assertThat(p.getLevel()).isEqualTo(ProductMatchLevel.NAMED);
        assertThat(planMatches.findByPlanPointIdIn(List.of(p.getId()))).hasSize(1);
    }

    @Test
    void vanished_markedGoneWithStatus() {
        search.plansByWord.put("рециркулятор", List.of(plan(1, "Рециркулятор", "бактерицидный", "Штука", "г.Алматы")));
        service.collect(new ImportSummary());
        search.plansByWord.clear();
        search.plansById.put(1L, new FoundPlanPoint(1L, 2026, 11, null, null, null, "Рециркулятор", null, null, null, null, null, null,
                null, 5, "Опубликован", null));
        service.collect(new ImportSummary());
        ProcurementPlanPoint p = points.findByExtId(1L).orElseThrow();
        assertThat(p.getGoneAt()).isNotNull();
        assertThat(p.getGoneStatus()).isEqualTo("Опубликован");
    }
}
