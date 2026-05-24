package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.EquipmentMatchResponse;
import com.vladoose.nir.dto.response.EquipmentMatchResponse.BestDistributor;
import com.vladoose.nir.dto.response.EquipmentMatchResponse.Breakdown;
import com.vladoose.nir.dto.response.EquipmentMatchResponse.Candidate;
import com.vladoose.nir.dto.response.EquipmentMatchResponse.SubScore;
import com.vladoose.nir.dto.response.EquipmentMatchResponse.WeightsUsed;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EquipmentScoringService {

    private static final double RECOMMEND_THRESHOLD = 60.0;

    private final TenderLotRepository lotRepo;
    private final MedEquipmentRepository equipRepo;
    private final EquipmentHistoryStatsService statsService;

    public EquipmentScoringService(TenderLotRepository lotRepo,
                                    MedEquipmentRepository equipRepo,
                                    EquipmentHistoryStatsService statsService) {
        this.lotRepo = lotRepo;
        this.equipRepo = equipRepo;
        this.statsService = statsService;
    }

    public EquipmentMatchResponse scoreLot(Long lotId, double[] weights, String presetName) {
        TenderLot lot = lotRepo.findById(lotId)
                .orElseThrow(() -> new NotFoundException("Lot not found: " + lotId));

        Long equipTypeId = lot.getEquipmentType() != null ? lot.getEquipmentType().getId() : null;
        List<MedEquipment> shortlist = equipRepo.findMatchingEquipment(
                equipTypeId, lot.getMaxLengthMm(), lot.getMaxWidthMm(), lot.getMaxHeightMm(), lot.getMaxWeightKg());

        Set<Long> equipIds = shortlist.stream().map(MedEquipment::getId).collect(Collectors.toSet());
        Set<Long> typeIds = shortlist.stream()
                .map(e -> e.getEquipmentType() != null ? e.getEquipmentType().getId() : null)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        var stats = statsService.collect(equipIds, typeIds);
        boolean hasHistory = statsService.hasAnyWon();

        List<Candidate> candidates = new ArrayList<>();
        for (MedEquipment e : shortlist) {
            candidates.add(buildCandidate(e, lot, stats, weights));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::getScore).reversed());
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).setRank(i + 1);
        }
        if (!candidates.isEmpty() && candidates.get(0).getScore() >= RECOMMEND_THRESHOLD) {
            candidates.get(0).setRecommended(true);
        }

        EquipmentMatchResponse resp = new EquipmentMatchResponse();
        resp.setLotId(lotId);
        resp.setLotMaxCost(lot.getMaxCost());
        resp.setHasHistory(hasHistory);
        resp.setPreset(presetName);
        WeightsUsed wu = new WeightsUsed();
        wu.setPrice(weights[0]); wu.setMargin(weights[1]); wu.setTrack(weights[2]); wu.setDim(weights[3]);
        resp.setWeightsUsed(wu);
        resp.setCandidates(candidates);
        return resp;
    }

    private Candidate buildCandidate(MedEquipment e, TenderLot lot,
                                      EquipmentHistoryStatsService.Stats stats,
                                      double[] w) {
        Long eId = e.getId();
        Long tId = e.getEquipmentType() != null ? e.getEquipmentType().getId() : null;

        BigDecimal avgCost = stats.avgOfferedCost.get(eId);
        SubScore price;
        BigDecimal estimatedPrice = null;
        BigDecimal estimatedMargin = null;
        if (avgCost != null && lot.getMaxCost() != null && lot.getMaxCost().signum() > 0) {
            double ratio = avgCost.doubleValue() / lot.getMaxCost().doubleValue();
            double v = Math.max(0.0, Math.min(100.0, 100.0 * (1.0 - ratio)));
            price = new SubScore(round1(v), false,
                    String.format("avg оф. %s ₽ при потолке %s",
                            fmt(avgCost), fmt(lot.getMaxCost())));
            estimatedPrice = avgCost;
            estimatedMargin = lot.getMaxCost().subtract(avgCost);
        } else {
            price = new SubScore(50.0, true, "нет истории цен");
        }

        BigDecimal avgMargin = tId != null ? stats.avgMarginByType.get(tId) : null;
        SubScore margin;
        if (avgMargin != null) {
            double v = Math.min(100.0, avgMargin.doubleValue() * 2.0);
            margin = new SubScore(round1(v), false,
                    String.format("ср. маржа по типу: %s %%", fmt(avgMargin)));
        } else {
            margin = new SubScore(50.0, true, "нет истории маржи");
        }

        int wins = stats.wins.getOrDefault(eId, 0);
        double trackV = Math.min(100.0, 25.0 * (Math.log(wins + 1) / Math.log(2)));
        SubScore track = new SubScore(round1(trackV), false, "побед: " + wins);

        SubScore dim = computeDimScore(e, lot);

        double score = w[0]*price.getValue() + w[1]*margin.getValue() + w[2]*track.getValue() + w[3]*dim.getValue();

        Candidate c = new Candidate();
        c.setEquipmentId(eId);
        c.setName(e.getName());
        c.setManufact(e.getManufact());
        c.setEquipType(e.getEquipmentType() != null ? e.getEquipmentType().getName() : null);
        c.setLengthMm(e.getLengthMm());
        c.setWidthMm(e.getWidthMm());
        c.setHeightMm(e.getHeightMm());
        c.setWeightKg(e.getWeightKg());
        c.setSpec(e.getSpec());
        c.setScore(round1(score));
        c.setRecommended(false);
        Breakdown b = new Breakdown();
        b.setPrice(price); b.setMargin(margin); b.setTrack(track); b.setDim(dim);
        c.setBreakdown(b);
        c.setEstimatedPrice(estimatedPrice);
        c.setEstimatedMargin(estimatedMargin);

        EquipmentHistoryStatsService.DistAgg best = stats.bestDistributor.get(eId);
        if (best != null) {
            BestDistributor bd = new BestDistributor();
            bd.setDistributorId(best.distributorId);
            bd.setName(best.distributorName);
            bd.setDealsCount(best.dealsCount());
            bd.setAvgMarginPercent(best.avgMargin());
            c.setBestDistributor(bd);
        }
        return c;
    }

    private SubScore computeDimScore(MedEquipment e, TenderLot lot) {
        double sumUsed = 0;
        int count = 0;
        if (lot.getMaxLengthMm() != null && lot.getMaxLengthMm() > 0 && e.getLengthMm() != null) {
            sumUsed += (double) e.getLengthMm() / lot.getMaxLengthMm(); count++;
        }
        if (lot.getMaxWidthMm() != null && lot.getMaxWidthMm() > 0 && e.getWidthMm() != null) {
            sumUsed += (double) e.getWidthMm() / lot.getMaxWidthMm(); count++;
        }
        if (lot.getMaxHeightMm() != null && lot.getMaxHeightMm() > 0 && e.getHeightMm() != null) {
            sumUsed += (double) e.getHeightMm() / lot.getMaxHeightMm(); count++;
        }
        if (lot.getMaxWeightKg() != null && lot.getMaxWeightKg().signum() > 0 && e.getWeightKg() != null) {
            sumUsed += e.getWeightKg().doubleValue() / lot.getMaxWeightKg().doubleValue(); count++;
        }
        if (count == 0) {
            return new SubScore(100.0, false, "габариты лота не заданы");
        }
        double avgUsed = sumUsed / count;
        double v = Math.max(0.0, 100.0 - 25.0 * avgUsed);
        return new SubScore(round1(v), false,
                String.format("загрузка габаритов: %d %%", (int) Math.round(avgUsed * 100)));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String fmt(BigDecimal v) {
        if (v == null) return "—";
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
