package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Единый канал отправки запросов КП: на каждого поставщика создаётся PriceRequest (SENT)
 * с позициями (лот + опционально модель) и уходит письмо (KpEmailComposer + EmailService).
 * Ошибка/отсутствие email НЕ валит запись — отражается флагом в SendResult.
 */
@Service
public class PriceRequestSendService {

    private static final Logger log = LoggerFactory.getLogger(PriceRequestSendService.class);

    public static final String REASON_NO_EMAIL = "NO_EMAIL";
    public static final String REASON_SEND_FAILED = "SEND_FAILED";

    public record SendItem(Long tenderLotId, Long medEquipmentId, Integer requestedQuantity) {}
    public record SendResult(Long priceRequestId, Long distributorId, String distributorName,
                             boolean emailSent, String reason) {}

    private final TenderService tenderService;
    private final TenderLotService tenderLotService;
    private final MedEquipmentService medEquipmentService;
    private final DistributorService distributorService;
    private final PriceRequestService priceRequestService;
    private final PriceRequestItemRepository itemRepository;
    private final KpEmailComposer composer;
    private final EmailService emailService;

    public PriceRequestSendService(TenderService tenderService,
                                   TenderLotService tenderLotService,
                                   MedEquipmentService medEquipmentService,
                                   DistributorService distributorService,
                                   PriceRequestService priceRequestService,
                                   PriceRequestItemRepository itemRepository,
                                   KpEmailComposer composer,
                                   EmailService emailService) {
        this.tenderService = tenderService;
        this.tenderLotService = tenderLotService;
        this.medEquipmentService = medEquipmentService;
        this.distributorService = distributorService;
        this.priceRequestService = priceRequestService;
        this.itemRepository = itemRepository;
        this.composer = composer;
        this.emailService = emailService;
    }

    @Transactional
    public List<SendResult> send(Long tenderId, List<Long> distributorIds, List<SendItem> items) {
        if (tenderId == null) throw new BadRequestException("Не указан тендер");
        if (distributorIds == null || distributorIds.isEmpty()) throw new BadRequestException("Не выбраны поставщики");
        if (items == null || items.isEmpty()) throw new BadRequestException("Не выбраны позиции");

        Tender tender = tenderService.findById(tenderId);

        record Line(TenderLot lot, MedEquipment equipment, int qty) {}
        List<Line> lines = new ArrayList<>();
        for (SendItem si : items) {
            if (si.tenderLotId() == null) throw new BadRequestException("Не указан лот в позиции");
            if (si.requestedQuantity() == null || si.requestedQuantity() < 1) {
                throw new BadRequestException("Количество в позиции должно быть не меньше 1");
            }
            TenderLot lot = tenderLotService.findById(si.tenderLotId());
            if (!lot.getTender().getId().equals(tenderId)) {
                throw new BadRequestException("Лот " + si.tenderLotId() + " не принадлежит тендеру " + tenderId);
            }
            MedEquipment eq = si.medEquipmentId() != null ? medEquipmentService.findById(si.medEquipmentId()) : null;
            lines.add(new Line(lot, eq, si.requestedQuantity()));
        }

        List<SendResult> results = new ArrayList<>();
        for (Long distId : distributorIds) {
            Distributor dist = distributorService.findById(distId);
            PriceRequest pr = PriceRequest.builder()
                    .tender(tender)
                    .distributor(dist)
                    .status("SENT")
                    .sentAt(OffsetDateTime.now())
                    .createdAt(OffsetDateTime.now())
                    .build();
            pr = priceRequestService.save(pr); // штампует market
            for (Line line : lines) {
                PriceRequestItem item = PriceRequestItem.builder()
                        .priceRequest(pr)
                        .tenderLot(line.lot())
                        .medEquipment(line.equipment())
                        .requestedQuantity(line.qty())
                        .build();
                pr.getItems().add(itemRepository.save(item));
            }
            results.add(dispatch(pr, dist));
        }
        return results;
    }

    private SendResult dispatch(PriceRequest pr, Distributor dist) {
        String to = dist.getEmail();
        if (to == null || to.isBlank()) {
            log.warn("КП id={} создан, но у поставщика «{}» нет email — письмо не отправлено", pr.getId(), dist.getName());
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_NO_EMAIL);
        }
        KpEmailComposer.Composed msg = composer.compose(pr);
        try {
            emailService.sendEmail(to, msg.subject(), msg.body());
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), true, null);
        } catch (Exception ex) {
            log.warn("Не удалось отправить КП id={} на {}: {}. Запрос сохранён в БД.", pr.getId(), to, ex.getMessage());
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_SEND_FAILED);
        }
    }
}
