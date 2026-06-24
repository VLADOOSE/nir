package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.AutoFillResponse;
import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.entity.PriceRequestItem;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.repository.ApplyItemRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ApplyAutoFillService {

    private final ActivityApplyRepository applyRepository;
    private final TenderLotRepository lotRepository;
    private final PriceRequestItemRepository itemRepository;
    private final ApplyItemRepository applyItemRepository;

    public ApplyAutoFillService(ActivityApplyRepository ar,
                                TenderLotRepository lr,
                                PriceRequestItemRepository ir,
                                ApplyItemRepository air) {
        this.applyRepository = ar;
        this.lotRepository = lr;
        this.itemRepository = ir;
        this.applyItemRepository = air;
    }

    @Transactional
    public AutoFillResponse autoFill(Long applyId) {
        return autoFill(applyId, 25.0);
    }

    /**
     * @param markupPercent наценка в %, например 25 → offered = procurement × 1.25,
     *   но не выше lot.maxCost (если он задан). null/0 → offered = procurement (без наценки).
     */
    @Transactional
    public AutoFillResponse autoFill(Long applyId, Double markupPercent) {
        ActivityApply apply = applyRepository.findById(applyId)
                .orElseThrow(() -> new NotFoundException("Заявка не найдена: " + applyId));
        double markup = markupPercent != null && markupPercent >= 0 ? markupPercent : 0.0;

        List<TenderLot> lots = lotRepository.findByTenderId(apply.getTender().getId());

        Set<Long> existingLotIds = applyItemRepository.findByApplyId(applyId).stream()
                .map(i -> i.getTenderLot() != null ? i.getTenderLot().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<PriceRequestItem> allTenderItems = itemRepository.findByTenderId(apply.getTender().getId());

        int added = 0;
        List<String> missing = new ArrayList<>();

        for (TenderLot lot : lots) {
            if (existingLotIds.contains(lot.getId())) {
                continue;
            }
            List<PriceRequestItem> candidates = allTenderItems.stream()
                    .filter(i -> i.getTenderLot().getId().equals(lot.getId()))
                    .filter(i -> i.getResponsePrice() != null)
                    .toList();
            if (candidates.isEmpty()) {
                missing.add("Лот " + lot.getLotNumber() + ": " + lot.getEquipName());
                continue;
            }
            PriceRequestItem best = candidates.stream()
                    .min(Comparator.comparing(PriceRequestItem::getResponsePrice))
                    .orElseThrow();
            if (best.getMedEquipment() == null) {
                // КП частной заявки может не иметь привязки к каталогу оборудования —
                // без med_equipment позицию заявки построить нельзя, считаем лот без КП.
                missing.add("Лот " + lot.getLotNumber() + ": " + lot.getEquipName());
                continue;
            }
            BigDecimal procurement = best.getResponsePrice();
            BigDecimal offered = procurement.multiply(
                    BigDecimal.valueOf(1.0 + markup / 100.0)
            ).setScale(2, RoundingMode.HALF_UP);
            if (lot.getMaxCost() != null && offered.compareTo(lot.getMaxCost()) > 0) {
                offered = lot.getMaxCost();
            }
            ApplyItem ai = ApplyItem.builder()
                    .apply(apply)
                    .tenderLot(lot)
                    .medEquipment(best.getMedEquipment())
                    .distributor(best.getPriceRequest().getDistributor())
                    .offeredCost(offered)
                    .quantity(lot.getQuantity())
                    .build();
            applyItemRepository.save(ai);
            added++;
        }

        AutoFillResponse resp = new AutoFillResponse();
        resp.setAddedItems(added);
        resp.setLotsWithoutResponse(missing);
        return resp;
    }
}
