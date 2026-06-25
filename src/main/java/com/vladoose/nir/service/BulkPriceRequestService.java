package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.util.KpToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BulkPriceRequestService {

    private static final Logger log = LoggerFactory.getLogger(BulkPriceRequestService.class);

    private final TenderRepository tenderRepository;
    private final TenderLotRepository tenderLotRepository;
    private final MedEquipmentService medEquipmentService;
    private final DistributorRepository distributorRepository;
    private final PriceRequestRepository priceRequestRepository;
    private final PriceRequestItemRepository priceRequestItemRepository;
    private final EmailService emailService;

    public BulkPriceRequestService(TenderRepository tr, TenderLotRepository tlr,
                                    MedEquipmentService mes, DistributorRepository dr,
                                    PriceRequestRepository prr, PriceRequestItemRepository prir,
                                    EmailService es) {
        this.tenderRepository = tr; this.tenderLotRepository = tlr; this.medEquipmentService = mes;
        this.distributorRepository = dr; this.priceRequestRepository = prr;
        this.priceRequestItemRepository = prir; this.emailService = es;
    }

    public record GroupItem(TenderLot lot, MedEquipment equipment) {}
    public record DistributorGroup(Distributor distributor, List<GroupItem> items) {}
    public record Preview(List<DistributorGroup> groups, List<TenderLot> lotsWithoutMatch, List<TenderLot> lotsWithoutDistributor) {}
    public record SendItem(Long tenderLotId, Long medEquipmentId, Integer requestedQuantity) {}

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

    @Transactional
    public PriceRequest sendGroup(Long tenderId, Long distributorId, List<SendItem> items) {
        Tender tender = tenderRepository.findById(tenderId).orElseThrow();
        Distributor dist = distributorRepository.findById(distributorId).orElseThrow();

        PriceRequest pr = PriceRequest.builder()
                .tender(tender)
                .distributor(dist)
                .status("SENT")
                .sentAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .build();
        priceRequestRepository.save(pr);

        for (SendItem si : items) {
            TenderLot lot = tenderLotRepository.findById(si.tenderLotId()).orElseThrow();
            MedEquipment eq = medEquipmentService.findById(si.medEquipmentId());
            PriceRequestItem item = PriceRequestItem.builder()
                    .priceRequest(pr).tenderLot(lot).medEquipment(eq)
                    .requestedQuantity(si.requestedQuantity()).build();
            priceRequestItemRepository.save(item);
        }

        String body = buildEmailBody(tender, dist, items);
        try {
            emailService.sendEmail(dist.getEmail() == null ? "" : dist.getEmail(),
                    KpToken.subjectToken(pr.getId())
                            + " Запрос КП по тендеру №" + tender.getTenderNumber(), body);
        } catch (Exception ex) {
            log.warn("Не удалось отправить КП на {}: {}. Запрос сохранён в БД (id={}).",
                    dist.getEmail(), ex.getMessage(), pr.getId());
        }
        return pr;
    }

    String buildEmailBody(Tender tender, Distributor dist, List<SendItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("Уважаемый(ая) ").append(safe(dist.getLastName())).append(" ").append(safe(dist.getFirstName())).append("!\n\n");
        sb.append("ООО «Регион-Мед» по тендеру №").append(tender.getTenderNumber())
          .append(" просит предоставить КП на следующие позиции:\n\n");
        for (SendItem it : items) {
            TenderLot lot = tenderLotRepository.findById(it.tenderLotId()).orElseThrow();
            MedEquipment eq = medEquipmentService.findById(it.medEquipmentId());
            sb.append("- Лот ").append(lot.getLotNumber()).append(": ")
              .append(eq.getName()).append(" (").append(eq.getManufact()).append(") × ")
              .append(it.requestedQuantity()).append(" шт.\n");
        }
        sb.append("\nПросим указать: цену за единицу, сроки поставки, условия оплаты, гарантию.\n\n");
        sb.append("С уважением,\nООО «Регион-Мед»");
        return sb.toString();
    }

    private String safe(String s) { return s == null ? "" : s; }
}
