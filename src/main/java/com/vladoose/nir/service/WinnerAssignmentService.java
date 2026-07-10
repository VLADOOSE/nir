package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.AssignWinnerResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Ручное назначение победителя по лоту: выбранный поставщик из сравнения → позиция заявки. */
@Service
public class WinnerAssignmentService {

    private final TenderService tenderService;
    private final PriceRequestItemRepository priceRequestItemRepository;
    private final ActivityApplyRepository applyRepository;

    public WinnerAssignmentService(TenderService tenderService,
                                   PriceRequestItemRepository priceRequestItemRepository,
                                   ActivityApplyRepository applyRepository) {
        this.tenderService = tenderService;
        this.priceRequestItemRepository = priceRequestItemRepository;
        this.applyRepository = applyRepository;
    }

    @Transactional
    public AssignWinnerResponse assignWinner(Long tenderId, Long lotId, Long priceRequestId, Double markupPercent) {
        Tender tender = tenderService.findById(tenderId); // аспект отсеет чужой рынок; гард ниже — defense-in-depth
        if (tender.getMarket() != null && tender.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Тендер не найден: id=" + tenderId);
        }

        PriceRequestItem item = priceRequestItemRepository.findByPriceRequestId(priceRequestId).stream()
                .filter(i -> i.getTenderLot() != null && i.getTenderLot().getId().equals(lotId))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Нет предложения поставщика по лоту"));
        if (item.getResponsePrice() == null) {
            throw new BadRequestException("У поставщика нет введённой цены по лоту");
        }
        if (item.getMedEquipment() == null) {
            throw new BadRequestException("У предложения нет привязки к каталогу оборудования — назначьте модель");
        }

        TenderLot lot = item.getTenderLot();
        double markup = markupPercent != null && markupPercent >= 0 ? markupPercent : 0.0;
        BigDecimal offered = item.getResponsePrice()
                .multiply(BigDecimal.valueOf(1.0 + markup / 100.0))
                .setScale(2, RoundingMode.HALF_UP);

        ActivityApply apply = applyRepository.findByTenderId(tenderId).stream()
                .filter(a -> "DRAFT".equals(a.getStatus()))
                .findFirst()
                .orElseGet(() -> ActivityApply.builder().tender(tender).status("DRAFT").build());

        ApplyItem target = apply.getItems().stream()
                .filter(ai -> ai.getTenderLot() != null && ai.getTenderLot().getId().equals(lotId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            target = ApplyItem.builder().apply(apply).tenderLot(lot).build();
            apply.getItems().add(target);
        }
        target.setMedEquipment(item.getMedEquipment());
        target.setDistributor(item.getPriceRequest().getDistributor());
        target.setOfferedCost(offered);
        target.setQuantity(lot.getQuantity());

        ActivityApply saved = applyRepository.save(apply); // cascade ALL сохранит/обновит item
        ApplyItem persisted = saved.getItems().stream()
                .filter(ai -> ai.getTenderLot() != null && ai.getTenderLot().getId().equals(lotId))
                .findFirst().orElse(target);

        return new AssignWinnerResponse(saved.getId(), persisted.getId(), lotId,
                item.getPriceRequest().getDistributor().getName(), offered);
    }
}
