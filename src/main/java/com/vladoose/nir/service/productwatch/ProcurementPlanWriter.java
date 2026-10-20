package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.dto.FoundPlanPoint;
import com.vladoose.nir.repository.ProcurementPlanMatchRepository;
import com.vladoose.nir.repository.ProcurementPlanPointRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/** Запись пунктов планов (спека §10.1): отдельный бин — транзакция на вызов, сеть сюда не заходит (§6 CLAUDE.md). */
@Component
public class ProcurementPlanWriter {

    /** Пункт плана с нашими группами: регион — по месту поставки, группы → слово, по которому пункт прошёл. */
    public record Collected(FoundPlanPoint point, String region, ProductMatchLevel level, Map<Long, String> groupWords) {}

    private final ProcurementPlanPointRepository points;
    private final ProcurementPlanMatchRepository matches;

    public ProcurementPlanWriter(ProcurementPlanPointRepository points, ProcurementPlanMatchRepository matches) {
        this.points = points; this.matches = matches;
    }

    @Transactional(readOnly = true)
    public boolean isEmpty() { return points.count() == 0; }

    /** @return {создано, обновлено} */
    @Transactional
    public int[] upsert(List<Collected> found, OffsetDateTime runStart) {
        int created = 0, updated = 0;
        for (Collected c : found) {
            FoundPlanPoint f = c.point();
            Optional<ProcurementPlanPoint> existing = points.findByExtId(f.id());
            ProcurementPlanPoint p = existing.orElseGet(() -> ProcurementPlanPoint.builder().market(Market.KZ).extId(f.id())
                    .firstSeenAt(runStart).build());
            if (existing.isPresent()) updated++; else created++;
            p.setYear(f.year() == null ? runStart.getYear() : f.year());
            p.setMonth(f.month());
            p.setCustomerBin(cut(f.subjectBiin(), 20));
            p.setCustomerName(cut(f.subjectNameRu(), 500));
            p.setRegion(c.region());
            p.setDeliveryPlace(f.deliveryPlace());
            p.setName(Objects.requireNonNullElse(cut(f.nameRu(), 500), "—"));
            String desc = String.join(". ", Arrays.asList(blank(f.descRu()), blank(f.extraDescRu())).stream().filter(Objects::nonNull).toList());
            p.setDescription(desc.isEmpty() ? null : desc);
            p.setQuantity(f.count());
            p.setUnit(cut(f.unit(), 100));
            p.setUnitPrice(f.price());
            p.setAmount(f.amount());
            p.setTradeMethod(cut(f.tradeMethod(), 200));
            p.setStatusId(f.statusId());
            p.setStatusName(cut(f.statusName(), 100));
            p.setLevel(c.level());
            p.setLastSeenAt(runStart);
            p.setGoneAt(null);
            p.setGoneStatus(null);
            p = points.save(p);
            // разницей, а не «удалить всё и вставить»: при сбросе Hibernate выполняет вставки раньше удалений, и повторный
            // сбор того же пункта упирался в уникальность (plan_point_id, group_id)
            Map<Long, ProcurementPlanMatch> old = new HashMap<>();
            for (ProcurementPlanMatch m : matches.findByPlanPointIdIn(List.of(p.getId()))) old.put(m.getGroupId(), m);
            for (Map.Entry<Long, String> g : c.groupWords().entrySet()) {
                ProcurementPlanMatch m = old.remove(g.getKey());
                if (m == null) m = ProcurementPlanMatch.builder().planPointId(p.getId()).groupId(g.getKey()).build();
                m.setWord(cut(g.getValue(), 100));
                matches.save(m);
            }
            matches.deleteAll(old.values());
        }
        return new int[] {created, updated};
    }

    /**
     * Открытые пункты, которые этот сбор не встретил, — их статус проверяется по id. По набору номеров сбора, а не по
     * времени «последний раз видели»: два сбора в одну секунду (и неподвижные часы теста) иначе не различались бы.
     */
    @Transactional(readOnly = true)
    public List<Long> openNotSeen(Set<Long> seenExtIds) {
        return points.findByGoneAtIsNull().stream().map(ProcurementPlanPoint::getExtId).filter(id -> !seenExtIds.contains(id)).toList();
    }

    /** extId → статус площадки («Опубликован», «снят»…): пункт уходит из списка по умолчанию. */
    @Transactional
    public void markGone(Map<Long, String> gone) {
        OffsetDateTime now = OffsetDateTime.now();
        for (Map.Entry<Long, String> e : gone.entrySet()) {
            points.findByExtId(e.getKey()).ifPresent(p -> { p.setGoneAt(now); p.setGoneStatus(cut(e.getValue(), 100)); });
        }
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.strip(); }

    private static String cut(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max); }
}
