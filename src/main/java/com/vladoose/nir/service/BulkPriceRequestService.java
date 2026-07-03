package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** Превью каталожного массового подбора КП (отправка — через единый PriceRequestSendService). */
@Service
public class BulkPriceRequestService {

    private final TenderRepository tenderRepository;
    private final TenderLotRepository tenderLotRepository;
    private final MedEquipmentService medEquipmentService;
    private final DistributorRepository distributorRepository;

    public BulkPriceRequestService(TenderRepository tr, TenderLotRepository tlr,
                                    MedEquipmentService mes, DistributorRepository dr) {
        this.tenderRepository = tr; this.tenderLotRepository = tlr; this.medEquipmentService = mes;
        this.distributorRepository = dr;
    }

    public record GroupItem(TenderLot lot, MedEquipment equipment) {}
    public record DistributorGroup(Distributor distributor, List<GroupItem> items) {}
    public record Preview(List<DistributorGroup> groups, List<TenderLot> lotsWithoutMatch, List<TenderLot> lotsWithoutDistributor) {}

    public Preview buildPreview(Long tenderId) {
        Tender tender = tenderRepository.findById(tenderId).orElseThrow(() -> new NotFoundException("Тендер не найден: " + tenderId));
        List<TenderLot> lots = tenderLotRepository.findByTenderId(tenderId);
        Map<Long, List<GroupItem>> byDistributor = new LinkedHashMap<>();
        List<TenderLot> lotsNoMatch = new ArrayList<>();
        List<TenderLot> lotsNoDist = new ArrayList<>();

        for (TenderLot lot : lots) {
            List<MedEquipment> models = medEquipmentService.findMatchingForLot(lot);
            if (models.isEmpty()) { lotsNoMatch.add(lot); continue; }
            if (lot.getEquipmentType() == null) { lotsNoMatch.add(lot); continue; }
            List<Distributor> eligible = distributorRepository.findEligibleForType(lot.getEquipmentType().getId());
            if (eligible.isEmpty()) { lotsNoDist.add(lot); continue; }
            for (Distributor d : eligible) {
                for (MedEquipment m : models) {
                    byDistributor.computeIfAbsent(d.getId(), k -> new ArrayList<>()).add(new GroupItem(lot, m));
                }
            }
        }

        List<DistributorGroup> groups = byDistributor.entrySet().stream()
                .map(e -> new DistributorGroup(
                        distributorRepository.findById(e.getKey()).orElseThrow(),
                        e.getValue()))
                .collect(Collectors.toList());
        return new Preview(groups, lotsNoMatch, lotsNoDist);
    }
}
