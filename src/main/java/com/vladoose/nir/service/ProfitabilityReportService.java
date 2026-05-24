package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.ApplyItemResponse;
import com.vladoose.nir.dto.response.ProfitabilityReportResponse;
import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.repository.ApplyItemRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProfitabilityReportService {

    private final ActivityApplyRepository applyRepository;
    private final ApplyItemRepository applyItemRepository;
    private final ApplyItemEnricher applyItemEnricher;

    public ProfitabilityReportService(ActivityApplyRepository ar,
                                       ApplyItemRepository air,
                                       ApplyItemEnricher e) {
        this.applyRepository = ar;
        this.applyItemRepository = air;
        this.applyItemEnricher = e;
    }

    public ProfitabilityReportResponse buildReport() {
        List<ActivityApply> wonApplies = applyRepository.findAll().stream()
                .filter(a -> "WON".equals(a.getStatus()))
                .toList();

        ProfitabilityReportResponse report = new ProfitabilityReportResponse();
        ProfitabilityReportResponse.Summary summary = new ProfitabilityReportResponse.Summary();
        summary.setWonApplies(wonApplies.size());

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalProcurement = BigDecimal.ZERO;

        List<ProfitabilityReportResponse.TopTender> topList = new ArrayList<>();
        Map<Long, DistributorAcc> distAcc = new LinkedHashMap<>();
        Map<String, TypeAcc> typeAcc = new LinkedHashMap<>();

        for (ActivityApply apply : wonApplies) {
            List<ApplyItem> items = applyItemRepository.findByApplyId(apply.getId());
            List<ApplyItemResponse> enriched = applyItemEnricher.toEnrichedResponseList(items);

            BigDecimal applyRevenue = BigDecimal.ZERO;
            BigDecimal applyProcurement = BigDecimal.ZERO;
            boolean applyHasProcurement = false;

            for (int idx = 0; idx < items.size(); idx++) {
                ApplyItem entity = items.get(idx);
                ApplyItemResponse it = enriched.get(idx);
                int qty = it.getQuantity() != null ? it.getQuantity() : 1;
                BigDecimal posRevenue = it.getOfferedCost() != null
                        ? it.getOfferedCost().multiply(BigDecimal.valueOf(qty))
                        : BigDecimal.ZERO;
                applyRevenue = applyRevenue.add(posRevenue);

                if (it.getProcurementCost() != null) {
                    BigDecimal posProcurement = it.getProcurementCost().multiply(BigDecimal.valueOf(qty));
                    applyProcurement = applyProcurement.add(posProcurement);
                    applyHasProcurement = true;

                    BigDecimal posProfit = posRevenue.subtract(posProcurement);

                    // По дистрибьюторам
                    if (entity.getDistributor() != null) {
                        DistributorAcc d = distAcc.computeIfAbsent(entity.getDistributor().getId(),
                                k -> new DistributorAcc(entity.getDistributor().getId(), entity.getDistributor().getName()));
                        d.dealsCount++;
                        d.totalProfit = d.totalProfit.add(posProfit);
                        if (posProcurement.compareTo(BigDecimal.ZERO) > 0) {
                            d.marginSum = d.marginSum.add(
                                    posProfit.multiply(BigDecimal.valueOf(100))
                                            .divide(posProcurement, 4, RoundingMode.HALF_UP));
                            d.marginCount++;
                        }
                    }

                    // По типам
                    String typeName = (entity.getMedEquipment() != null && entity.getMedEquipment().getEquipmentType() != null)
                            ? entity.getMedEquipment().getEquipmentType().getName()
                            : "—";
                    TypeAcc t = typeAcc.computeIfAbsent(typeName, k -> new TypeAcc(typeName));
                    t.positionsCount++;
                    t.totalProfit = t.totalProfit.add(posProfit);
                    if (posProcurement.compareTo(BigDecimal.ZERO) > 0) {
                        t.marginSum = t.marginSum.add(
                                posProfit.multiply(BigDecimal.valueOf(100))
                                        .divide(posProcurement, 4, RoundingMode.HALF_UP));
                        t.marginCount++;
                    }
                }
            }

            totalRevenue = totalRevenue.add(applyRevenue);
            totalProcurement = totalProcurement.add(applyProcurement);

            if (applyHasProcurement) {
                ProfitabilityReportResponse.TopTender tt = new ProfitabilityReportResponse.TopTender();
                tt.setApplyId(apply.getId());
                tt.setTenderNumber(apply.getTender() != null ? apply.getTender().getTenderNumber() : "—");
                tt.setFacilityName(apply.getTender() != null && apply.getTender().getFacility() != null
                        ? apply.getTender().getFacility().getName() : "—");
                tt.setRevenue(applyRevenue);
                BigDecimal profit = applyRevenue.subtract(applyProcurement);
                tt.setProfit(profit);
                if (applyProcurement.compareTo(BigDecimal.ZERO) > 0) {
                    tt.setMarginPercent(profit.multiply(BigDecimal.valueOf(100))
                            .divide(applyProcurement, 2, RoundingMode.HALF_UP));
                }
                topList.add(tt);
            }
        }

        summary.setTotalRevenue(totalRevenue);
        summary.setTotalProcurement(totalProcurement);
        BigDecimal totalProfit = totalRevenue.subtract(totalProcurement);
        summary.setTotalProfit(totalProfit);
        if (totalProcurement.compareTo(BigDecimal.ZERO) > 0) {
            summary.setMarginPercent(totalProfit.multiply(BigDecimal.valueOf(100))
                    .divide(totalProcurement, 2, RoundingMode.HALF_UP));
        }
        if (wonApplies.size() > 0) {
            summary.setAvgChequeProfit(totalProfit.divide(BigDecimal.valueOf(wonApplies.size()), 2, RoundingMode.HALF_UP));
        }
        report.setSummary(summary);

        // Топ-5 тендеров по прибыли
        topList.sort(Comparator.comparing(ProfitabilityReportResponse.TopTender::getProfit, Comparator.nullsLast(Comparator.reverseOrder())));
        report.setTopTenders(topList.stream().limit(5).toList());

        // Рейтинг дистрибьюторов
        report.setDistributorRanking(distAcc.values().stream().map(d -> {
            ProfitabilityReportResponse.DistributorMargin dm = new ProfitabilityReportResponse.DistributorMargin();
            dm.setDistributorId(d.id);
            dm.setName(d.name);
            dm.setDealsCount(d.dealsCount);
            dm.setTotalProfit(d.totalProfit);
            if (d.marginCount > 0) {
                dm.setAvgMarginPercent(d.marginSum.divide(BigDecimal.valueOf(d.marginCount), 2, RoundingMode.HALF_UP));
            }
            return dm;
        }).sorted(Comparator.comparing(ProfitabilityReportResponse.DistributorMargin::getTotalProfit, Comparator.nullsLast(Comparator.reverseOrder()))).toList());

        // Прибыль по типам
        report.setProfitByType(typeAcc.values().stream().map(t -> {
            ProfitabilityReportResponse.TypeProfit tp = new ProfitabilityReportResponse.TypeProfit();
            tp.setTypeName(t.name);
            tp.setPositionsCount(t.positionsCount);
            tp.setTotalProfit(t.totalProfit);
            if (t.marginCount > 0) {
                tp.setAvgMarginPercent(t.marginSum.divide(BigDecimal.valueOf(t.marginCount), 2, RoundingMode.HALF_UP));
            }
            return tp;
        }).sorted(Comparator.comparing(ProfitabilityReportResponse.TypeProfit::getTotalProfit, Comparator.nullsLast(Comparator.reverseOrder()))).collect(Collectors.toList()));

        return report;
    }

    private static class DistributorAcc {
        final Long id;
        final String name;
        int dealsCount = 0;
        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal marginSum = BigDecimal.ZERO;
        int marginCount = 0;
        DistributorAcc(Long id, String name) { this.id = id; this.name = name; }
    }

    private static class TypeAcc {
        final String name;
        int positionsCount = 0;
        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal marginSum = BigDecimal.ZERO;
        int marginCount = 0;
        TypeAcc(String name) { this.name = name; }
    }
}
