package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.KpPreviewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.util.KpToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Единый канал отправки запросов КП: на каждого поставщика создаётся PriceRequest (SENT)
 * с позициями (лот + опционально модель) и уходит письмо (KpEmailComposer + EmailService).
 * Запись КП — в отдельном @Transactional-бине ({@link PriceRequestWriter}); письмо шлётся ПОСЛЕ коммита
 * (сеть вне транзакции, §6). Ошибка/отсутствие email НЕ валит запись — отражается флагом в SendResult.
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
    private final PriceRequestWriter writer;
    private final KpEmailComposer composer;
    private final EmailService emailService;

    public PriceRequestSendService(TenderService tenderService,
                                   TenderLotService tenderLotService,
                                   MedEquipmentService medEquipmentService,
                                   DistributorService distributorService,
                                   PriceRequestWriter writer,
                                   KpEmailComposer composer,
                                   EmailService emailService) {
        this.tenderService = tenderService;
        this.tenderLotService = tenderLotService;
        this.medEquipmentService = medEquipmentService;
        this.distributorService = distributorService;
        this.writer = writer;
        this.composer = composer;
        this.emailService = emailService;
    }

    public List<SendResult> send(Long tenderId, List<Long> distributorIds, List<SendItem> items,
                                 String subjectOverride, String bodyOverride) {
        if (tenderId == null) throw new BadRequestException("Не указан тендер");
        if (distributorIds == null || distributorIds.isEmpty()) throw new BadRequestException("Не выбраны поставщики");
        if (items == null || items.isEmpty()) throw new BadRequestException("Не выбраны позиции");

        // findById == em.find обходит hibernate-фильтр рынка → явные гарды от чужого рынка (как в setProposedEquipment)
        Tender tender = tenderService.findById(tenderId);
        requireCurrentMarket(tender.getMarket(), "Тендер не найден: " + tenderId);

        List<PriceRequestWriter.Line> lines = new ArrayList<>();
        for (SendItem si : items) {
            if (si.tenderLotId() == null) throw new BadRequestException("Не указан лот в позиции");
            if (si.requestedQuantity() == null || si.requestedQuantity() < 1) {
                throw new BadRequestException("Количество в позиции должно быть не меньше 1");
            }
            TenderLot lot = tenderLotService.findById(si.tenderLotId());
            if (!lot.getTender().getId().equals(tenderId)) {
                throw new BadRequestException("Лот " + si.tenderLotId() + " не принадлежит тендеру " + tenderId);
            }
            MedEquipment eq = null;
            if (si.medEquipmentId() != null) {
                eq = medEquipmentService.findById(si.medEquipmentId());
                requireCurrentMarket(eq.getMarket(), "Оборудование не найдено: " + si.medEquipmentId());
            }
            lines.add(new PriceRequestWriter.Line(lot, eq, si.requestedQuantity()));
        }

        List<SendResult> results = new ArrayList<>();
        for (Long distId : new LinkedHashSet<>(distributorIds)) { // дедуп: один поставщик — одно КП/письмо
            Distributor dist = distributorService.findById(distId);
            requireCurrentMarket(dist.getMarket(), "Поставщик не найден: " + distId);
            PriceRequest pr = writer.persist(tender, dist, lines); // коммит записи
            results.add(dispatch(pr, dist, subjectOverride, bodyOverride)); // письмо ПОСЛЕ коммита
        }
        return results;
    }

    private void requireCurrentMarket(Market market, String notFoundMessage) {
        if (market != null && market != MarketContext.get()) {
            throw new NotFoundException(notFoundMessage);
        }
    }

    private SendResult dispatch(PriceRequest pr, Distributor dist, String subjectOverride, String bodyOverride) {
        String to = dist.getEmail();
        if (to == null || to.isBlank()) {
            log.warn("КП id={} создан, но у поставщика «{}» нет email — письмо не отправлено", pr.getId(), dist.getName());
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_NO_EMAIL);
        }
        KpEmailComposer.Composed def = composer.compose(pr);
        // токен [КП-id] ВСЕГДА серверный: при override человеческой части клеим его отдельно
        String subject = (subjectOverride != null && !subjectOverride.isBlank())
                ? KpToken.subjectToken(pr.getId()) + " " + subjectOverride.trim()
                : def.subject();
        String body = (bodyOverride != null && !bodyOverride.isBlank()) ? bodyOverride : def.body();
        try {
            emailService.sendEmail(to, subject, body);
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), true, null);
        } catch (Exception ex) {
            log.warn("Не удалось отправить КП id={} на {}: {}. Запрос сохранён в БД.", pr.getId(), to, ex.getMessage());
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_SEND_FAILED);
        }
    }

    /** Черновой предпросмотр текста КП (образец по первому поставщику, PriceRequest НЕ сохраняется, темы без токена). */
    public KpPreviewResponse preview(Long tenderId, List<Long> distributorIds, List<SendItem> items) {
        if (tenderId == null) throw new BadRequestException("Не указан тендер");
        if (distributorIds == null || distributorIds.isEmpty()) throw new BadRequestException("Не выбраны поставщики");
        if (items == null || items.isEmpty()) throw new BadRequestException("Не выбраны позиции");
        Tender tender = tenderService.findById(tenderId);
        requireCurrentMarket(tender.getMarket(), "Тендер не найден: " + tenderId);
        List<PriceRequestItem> prItems = new ArrayList<>();
        for (SendItem si : items) {
            if (si.tenderLotId() == null) throw new BadRequestException("Не указан лот в позиции");
            TenderLot lot = tenderLotService.findById(si.tenderLotId());
            if (!lot.getTender().getId().equals(tenderId)) {
                throw new BadRequestException("Лот " + si.tenderLotId() + " не принадлежит тендеру " + tenderId);
            }
            MedEquipment eq = si.medEquipmentId() != null ? medEquipmentService.findById(si.medEquipmentId()) : null;
            prItems.add(PriceRequestItem.builder().tenderLot(lot).medEquipment(eq)
                    .requestedQuantity(si.requestedQuantity()).build());
        }
        Distributor sample = distributorService.findById(distributorIds.get(0));
        requireCurrentMarket(sample.getMarket(), "Поставщик не найден");
        PriceRequest draft = PriceRequest.builder().tender(tender).distributor(sample)
                .market(tender.getMarket()).items(prItems).build();
        KpEmailComposer.Composed c = composer.composeForPreview(draft);
        return new KpPreviewResponse(c.subject(), c.body());
    }
}
