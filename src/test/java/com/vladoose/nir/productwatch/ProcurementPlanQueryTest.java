package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PlanPointDto;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.ProcurementPlanQuery;
import com.vladoose.nir.service.productwatch.ProcurementPlanQuery.Filter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Страница «Планы закупок» (спека §10.2): по умолчанию — открытые; фильтры группы, региона, «скоро», «новые»; «месяц прошёл». */
@SpringBootTest
@Transactional
class ProcurementPlanQueryTest {

    @Autowired ProcurementPlanPointRepository points;
    @Autowired ProcurementPlanMatchRepository matches;
    @Autowired ProductGroupRepository groups;
    ProcurementPlanQuery query;

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        points.deleteAllInBatch();
        query = new ProcurementPlanQuery(points, matches, groups, CLOCK);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    ProcurementPlanPoint point(long extId, int year, int month, String region, int seenDaysAgo, String gone) {
        OffsetDateTime seen = OffsetDateTime.now(CLOCK).minusDays(seenDaysAgo);
        return points.save(ProcurementPlanPoint.builder().market(Market.KZ).extId(extId).year(year).month(month).region(region)
                .name("Рециркулятор " + extId).amount(new BigDecimal(1000 * extId)).level(ProductMatchLevel.TYPE)
                .firstSeenAt(seen).lastSeenAt(seen).goneAt(gone == null ? null : seen).goneStatus(gone).build());
    }

    List<Long> ids(Filter f) { return query.list(f).stream().map(PlanPointDto::extId).toList(); }

    static Filter f(Long group, String region, boolean soon, boolean fresh, boolean all) {
        return new Filter(group, region, soon, fresh, all, null, "month", 300);
    }

    @Test
    void filters_soonFreshGroupRegionAll_monthPassed() {
        ProductGroup g = groups.save(ProductGroup.builder().market(Market.KZ).name("Рециркуляторы PW").searchWords(List.of("рециркулятор"))
                .position(99).build());
        point(1, 2026, 9, "г. Алматы", 30, null);
        ProcurementPlanPoint nov = point(2, 2026, 11, "Актюбинская область", 1, null);
        point(3, 2027, 3, "г. Алматы", 1, null);
        point(4, 2026, 12, "г. Алматы", 1, "Опубликован");
        matches.save(ProcurementPlanMatch.builder().planPointId(nov.getId()).groupId(g.getId()).word("рециркулятор").build());

        // по месяцу: ближайшие сверху (с текущего), прошедшие — ниже, свежие из них первыми
        assertThat(ids(f(null, null, false, false, false))).containsExactly(2L, 3L, 1L);
        assertThat(ids(f(null, null, true, false, false))).containsExactly(2L);          // октябрь — декабрь 2026
        assertThat(ids(f(null, null, false, true, false))).containsExactly(2L, 3L);      // за 7 дней
        assertThat(ids(f(g.getId(), null, false, false, false))).containsExactly(2L);
        assertThat(ids(f(null, "г. Алматы", false, false, true))).containsExactly(4L, 3L, 1L);
        assertThat(query.list(f(null, null, false, false, false)).get(2).monthPassed()).isTrue();
        assertThat(query.list(f(null, null, false, false, false)).get(0).monthPassed()).isFalse();
        assertThat(query.list(f(g.getId(), null, false, false, false)).get(0).groups()).containsExactly("Рециркуляторы PW");
    }

    @Test
    void rfMarket_empty() {
        point(1, 2026, 11, "г. Алматы", 1, null);
        MarketContext.set(Market.RF);
        assertThat(query.list(f(null, null, false, false, false))).isEmpty();
    }
}
