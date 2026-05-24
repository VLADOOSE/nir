package com.vladoose.nir.service;

import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.repository.ApplyItemRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EquipmentHistoryStatsService {

    private final ApplyItemRepository applyItemRepository;
    private final ActivityApplyRepository activityApplyRepository;
    private final ApplyItemEnricher enricher;

    public EquipmentHistoryStatsService(ApplyItemRepository ai,
                                         ActivityApplyRepository aa,
                                         ApplyItemEnricher e) {
        this.applyItemRepository = ai;
        this.activityApplyRepository = aa;
        this.enricher = e;
    }

    public Stats collect(Set<Long> equipmentIds, Set<Long> equipTypeIds) {
        Stats stats = new Stats();
        if (equipmentIds.isEmpty()) {
            return stats;
        }

        Map<Long, BigDecimal> costSum = new HashMap<>();
        Map<Long, Integer> costCount = new HashMap<>();
        for (ApplyItem item : applyItemRepository.findByMedEquipmentIdIn(equipmentIds)) {
            if (item.getMedEquipment() == null || item.getOfferedCost() == null) continue;
            Long eId = item.getMedEquipment().getId();
            costSum.merge(eId, item.getOfferedCost(), BigDecimal::add);
            costCount.merge(eId, 1, Integer::sum);
        }
        costSum.forEach((eId, sum) -> {
            int n = costCount.getOrDefault(eId, 0);
            if (n > 0) {
                stats.avgOfferedCost.put(eId, sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP));
            }
        });

        Map<Long, Set<Long>> winsByEquip = new HashMap<>();
        Map<Long, BigDecimal> marginSumByType = new HashMap<>();
        Map<Long, Integer> marginCountByType = new HashMap<>();
        Map<String, DistAgg> distAgg = new HashMap<>();

        for (ActivityApply apply : activityApplyRepository.findByStatus("WON")) {
            List<ApplyItem> items = applyItemRepository.findByApplyId(apply.getId());
            var enriched = enricher.toEnrichedResponseList(items);
            for (int i = 0; i < items.size(); i++) {
                ApplyItem item = items.get(i);
                MedEquipment me = item.getMedEquipment();
                if (me == null) continue;
                Long eId = me.getId();
                Long tId = me.getEquipmentType() != null ? me.getEquipmentType().getId() : null;
                Distributor d = item.getDistributor();
                BigDecimal marginPct = enriched.get(i).getMarginPercent();

                if (equipmentIds.contains(eId)) {
                    winsByEquip.computeIfAbsent(eId, k -> new HashSet<>()).add(apply.getId());
                }
                if (tId != null && equipTypeIds.contains(tId) && marginPct != null) {
                    marginSumByType.merge(tId, marginPct, BigDecimal::add);
                    marginCountByType.merge(tId, 1, Integer::sum);
                }
                if (equipmentIds.contains(eId) && d != null && marginPct != null) {
                    String key = eId + ":" + d.getId();
                    distAgg.computeIfAbsent(key, k -> new DistAgg(eId, d.getId(), d.getName()))
                            .accumulate(marginPct, apply.getId());
                }
            }
        }
        winsByEquip.forEach((eId, applies) -> stats.wins.put(eId, applies.size()));
        marginSumByType.forEach((tId, sum) -> {
            int n = marginCountByType.getOrDefault(tId, 0);
            if (n > 0) {
                stats.avgMarginByType.put(tId, sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP));
            }
        });
        Map<Long, DistAgg> bestPerEquip = new HashMap<>();
        for (DistAgg agg : distAgg.values()) {
            DistAgg current = bestPerEquip.get(agg.equipmentId);
            if (current == null || agg.avgMargin().compareTo(current.avgMargin()) > 0) {
                bestPerEquip.put(agg.equipmentId, agg);
            }
        }
        stats.bestDistributor.putAll(bestPerEquip);
        return stats;
    }

    public boolean hasAnyWon() {
        return activityApplyRepository.existsByStatus("WON");
    }

    public static class Stats {
        public final Map<Long, BigDecimal> avgOfferedCost = new HashMap<>();
        public final Map<Long, Integer> wins = new HashMap<>();
        public final Map<Long, BigDecimal> avgMarginByType = new HashMap<>();
        public final Map<Long, DistAgg> bestDistributor = new HashMap<>();
    }

    public static class DistAgg {
        public final Long equipmentId;
        public final Long distributorId;
        public final String distributorName;
        private BigDecimal sum = BigDecimal.ZERO;
        private int count = 0;
        private final Set<Long> appliesSeen = new HashSet<>();

        public DistAgg(Long equipmentId, Long distributorId, String distributorName) {
            this.equipmentId = equipmentId;
            this.distributorId = distributorId;
            this.distributorName = distributorName;
        }
        public void accumulate(BigDecimal marginPct, Long applyId) {
            sum = sum.add(marginPct);
            count++;
            appliesSeen.add(applyId);
        }
        public BigDecimal avgMargin() {
            return count == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        }
        public int dealsCount() { return appliesSeen.size(); }
    }
}
