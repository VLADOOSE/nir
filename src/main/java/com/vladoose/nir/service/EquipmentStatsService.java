package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.EquipmentStatsResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.PriceRequestItem;
import com.vladoose.nir.mapper.DistributorMapper;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class EquipmentStatsService {

    private final MedEquipmentRepository equipmentRepository;
    private final DistributorRepository distributorRepository;
    private final PriceRequestItemRepository itemRepository;
    private final DistributorMapper distributorMapper;

    public EquipmentStatsService(MedEquipmentRepository er, DistributorRepository dr,
                                 PriceRequestItemRepository ir, DistributorMapper dm) {
        this.equipmentRepository = er;
        this.distributorRepository = dr;
        this.itemRepository = ir;
        this.distributorMapper = dm;
    }

    public EquipmentStatsResponse buildStats(Long equipmentId) {
        MedEquipment eq = equipmentRepository.findById(equipmentId).orElseThrow();
        Long typeId = eq.getEquipmentType() != null ? eq.getEquipmentType().getId() : null;
        List<Distributor> potential = typeId != null
                ? distributorRepository.findEligibleForType(typeId)
                : List.of();

        List<PriceRequestItem> items = itemRepository.findByMedEquipmentId(equipmentId);
        List<PriceRequestItem> withPrice = items.stream()
                .filter(i -> i.getResponsePrice() != null)
                .toList();

        EquipmentStatsResponse r = new EquipmentStatsResponse();
        r.setPotentialDistributors(potential.stream().map(distributorMapper::toResponse).toList());

        EquipmentStatsResponse.Summary summary = new EquipmentStatsResponse.Summary();
        summary.setRequestsCount(items.size());
        summary.setDistinctDistributors((int) items.stream()
                .map(i -> i.getPriceRequest().getDistributor().getId())
                .distinct().count());
        if (!withPrice.isEmpty()) {
            BigDecimal min = withPrice.stream().map(PriceRequestItem::getResponsePrice).min(BigDecimal::compareTo).orElseThrow();
            BigDecimal max = withPrice.stream().map(PriceRequestItem::getResponsePrice).max(BigDecimal::compareTo).orElseThrow();
            BigDecimal sum = withPrice.stream().map(PriceRequestItem::getResponsePrice).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(withPrice.size()), 2, RoundingMode.HALF_UP);
            summary.setMinPrice(min);
            summary.setMaxPrice(max);
            summary.setAvgPrice(avg);
        }
        r.setSummary(summary);

        Map<Long, List<PriceRequestItem>> byDist = withPrice.stream()
                .collect(Collectors.groupingBy(i -> i.getPriceRequest().getDistributor().getId()));
        r.setRanking(byDist.entrySet().stream().map(e -> {
            EquipmentStatsResponse.DistributorRating rating = new EquipmentStatsResponse.DistributorRating();
            Distributor d = e.getValue().get(0).getPriceRequest().getDistributor();
            rating.setDistributor(distributorMapper.toResponse(d));
            rating.setResponsesCount(e.getValue().size());
            BigDecimal sum = e.getValue().stream().map(PriceRequestItem::getResponsePrice).reduce(BigDecimal.ZERO, BigDecimal::add);
            rating.setAvgPrice(sum.divide(BigDecimal.valueOf(e.getValue().size()), 2, RoundingMode.HALF_UP));
            return rating;
        }).sorted(Comparator.comparing(EquipmentStatsResponse.DistributorRating::getAvgPrice)).toList());

        r.setHistory(items.stream()
                .sorted(Comparator.comparing((PriceRequestItem i) -> i.getPriceRequest().getCreatedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(i -> {
                    EquipmentStatsResponse.HistoryEntry h = new EquipmentStatsResponse.HistoryEntry();
                    h.setDate(i.getPriceRequest().getCreatedAt());
                    h.setDistributor(distributorMapper.toResponse(i.getPriceRequest().getDistributor()));
                    h.setTenderNumber(i.getPriceRequest().getTender().getTenderNumber());
                    h.setRequestedQuantity(i.getRequestedQuantity());
                    h.setResponsePrice(i.getResponsePrice());
                    h.setStatus(i.getPriceRequest().getStatus());
                    return h;
                }).toList());

        return r;
    }
}
