package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PlanPointDto;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProcurementPlanMatch;
import com.vladoose.nir.entity.ProcurementPlanPoint;
import com.vladoose.nir.repository.ProcurementPlanMatchRepository;
import com.vladoose.nir.repository.ProcurementPlanPointRepository;
import com.vladoose.nir.repository.ProductGroupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Stream;

/** Выборка страницы «Планы закупок» (спека §10.2). Пунктов — сотни: фильтры в памяти, рынок — фильтром Hibernate. */
@Service
public class ProcurementPlanQuery {

    public record Filter(Long group, String region, boolean soon, boolean fresh, boolean all, String q, String sort, int limit) {}

    private final ProcurementPlanPointRepository points;
    private final ProcurementPlanMatchRepository matches;
    private final ProductGroupRepository groups;
    private final Clock clock;

    @Autowired
    public ProcurementPlanQuery(ProcurementPlanPointRepository points, ProcurementPlanMatchRepository matches, ProductGroupRepository groups) {
        this(points, matches, groups, Clock.systemUTC());
    }

    public ProcurementPlanQuery(ProcurementPlanPointRepository points, ProcurementPlanMatchRepository matches, ProductGroupRepository groups,
                                Clock clock) {
        this.points = points; this.matches = matches; this.groups = groups; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PlanPointDto> list(Filter f) {
        if (MarketContext.get() != Market.KZ) return List.of();
        List<ProcurementPlanPoint> ps = f.all() ? points.findAll() : points.findByGoneAtIsNull();
        Map<Long, List<Long>> groupIds = new HashMap<>();
        for (ProcurementPlanMatch m : matches.findByPlanPointIdIn(ps.stream().map(ProcurementPlanPoint::getId).toList())) {
            groupIds.computeIfAbsent(m.getPlanPointId(), k -> new ArrayList<>()).add(m.getGroupId());
        }
        Map<Long, String> names = new HashMap<>();
        groups.findAllById(groupIds.values().stream().flatMap(List::stream).distinct().toList()).forEach(g -> names.put(g.getId(), g.getName()));
        YearMonth now = YearMonth.now(clock.withZone(Market.KZ.zone()));
        OffsetDateTime freshEdge = OffsetDateTime.now(clock).minusDays(7);
        String q = f.q() == null ? "" : f.q().strip().toLowerCase(Locale.ROOT);
        Comparator<ProcurementPlanPoint> order = switch (f.sort() == null ? "month" : f.sort()) {
            case "amount" -> Comparator.comparing(ProcurementPlanPoint::getAmount, Comparator.nullsLast(Comparator.reverseOrder()));
            case "new" -> Comparator.comparing(ProcurementPlanPoint::getFirstSeenAt, Comparator.reverseOrder());
            // по месяцу: ближайшие сверху (с текущего), прошедшие — ниже, свежие из них первыми
            default -> Comparator.comparing((ProcurementPlanPoint p) -> passed(p, now))
                    .thenComparingInt(p -> passed(p, now) ? -ym(p) : ym(p))
                    .thenComparing(ProcurementPlanPoint::getAmount, Comparator.nullsLast(Comparator.reverseOrder()));
        };
        return ps.stream()
                .filter(p -> f.group() == null || groupIds.getOrDefault(p.getId(), List.of()).contains(f.group()))
                .filter(p -> f.region() == null || f.region().isBlank() || f.region().equals(p.getRegion()))
                .filter(p -> !f.soon() || soon(p, now))
                .filter(p -> !f.fresh() || p.getFirstSeenAt().isAfter(freshEdge))
                .filter(p -> q.isEmpty() || Stream.of(p.getName(), p.getDescription(), p.getCustomerName())
                        .anyMatch(x -> x != null && x.toLowerCase(Locale.ROOT).contains(q)))
                .sorted(order)
                .limit(Math.max(1, Math.min(500, f.limit())))
                .map(p -> dto(p, groupIds.getOrDefault(p.getId(), List.of()).stream().map(names::get).filter(Objects::nonNull).toList(), now))
                .toList();
    }

    /** Месяц закупки прошёл (месяц не указан — прошёл год). */
    static boolean passed(ProcurementPlanPoint p, YearMonth now) {
        return p.getMonth() != null ? YearMonth.of(p.getYear(), p.getMonth()).isBefore(now) : p.getYear() < now.getYear();
    }

    private static int ym(ProcurementPlanPoint p) {
        return p.getYear() * 100 + (p.getMonth() == null ? 0 : p.getMonth());
    }

    /** Месяц закупки — в ближайшие 3 месяца, включая текущий. */
    static boolean soon(ProcurementPlanPoint p, YearMonth now) {
        if (p.getMonth() == null) return false;
        YearMonth ym = YearMonth.of(p.getYear(), p.getMonth());
        return !ym.isBefore(now) && !ym.isAfter(now.plusMonths(2));
    }

    private static PlanPointDto dto(ProcurementPlanPoint p, List<String> groupNames, YearMonth now) {
        boolean passed = passed(p, now);
        return new PlanPointDto(p.getId(), p.getExtId(), p.getYear(), p.getMonth(), passed, p.getCustomerName(), p.getRegion(),
                p.getName(), p.getDescription(), p.getQuantity(), p.getUnit(), p.getUnitPrice(), p.getAmount(), p.getTradeMethod(),
                groupNames, p.getLevel().name(), p.getFirstSeenAt(), p.getGoneStatus());
    }
}
