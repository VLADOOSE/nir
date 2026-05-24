package com.vladoose.nir.service;

import com.vladoose.nir.repository.ApplyItemRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private final TenderRepository tenderRepository;
    private final TenderLotRepository tenderLotRepository;
    private final ApplyItemRepository applyItemRepository;
    private final PriceRequestRepository priceRequestRepository;

    public ReportService(TenderRepository tenderRepository,
                         TenderLotRepository tenderLotRepository,
                         ApplyItemRepository applyItemRepository,
                         PriceRequestRepository priceRequestRepository) {
        this.tenderRepository = tenderRepository;
        this.tenderLotRepository = tenderLotRepository;
        this.applyItemRepository = applyItemRepository;
        this.priceRequestRepository = priceRequestRepository;
    }

    public Map<String, Long> getTenderStatsByStatus() {
        return tenderRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        t -> t.getStatus(),
                        LinkedHashMap::new,
                        Collectors.counting()));
    }

    public Map<String, Long> getEquipmentDemand() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : tenderLotRepository.countByEquipType()) {
            result.put((String) row[0], (Long) row[1]);
        }
        return result;
    }

    public List<Map<String, Object>> getDistributorStats() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : applyItemRepository.distributorStats()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("distributor", row[0]);
            entry.put("applyCount", row[1]);
            entry.put("avgPrice", row[2]);
            result.add(entry);
        }
        return result;
    }

    public List<Map<String, Object>> getDistributorPriceRequestStats() {
        return priceRequestRepository.distributorPriceRequestStats().stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", row[0]);
            map.put("totalRequests", row[1]);
            map.put("responded", row[2]);
            map.put("avgPrice", row[3]);
            return map;
        }).toList();
    }
}
