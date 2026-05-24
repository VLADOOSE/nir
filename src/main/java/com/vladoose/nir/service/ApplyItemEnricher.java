package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.ApplyItemResponse;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.entity.PriceRequestItem;
import com.vladoose.nir.mapper.ApplyItemMapper;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Добавляет к ApplyItemResponse рассчитанные поля procurementCost / margin / marginPercent,
 * подтягивая закупочную цену из последнего отклика КП по той же связке (лот + оборудование + дистрибьютор).
 */
@Component
public class ApplyItemEnricher {

    private final ApplyItemMapper mapper;
    private final PriceRequestItemRepository priceRequestItemRepository;

    public ApplyItemEnricher(ApplyItemMapper mapper, PriceRequestItemRepository priceRequestItemRepository) {
        this.mapper = mapper;
        this.priceRequestItemRepository = priceRequestItemRepository;
    }

    public ApplyItemResponse toEnrichedResponse(ApplyItem entity) {
        ApplyItemResponse r = mapper.toResponse(entity);
        enrich(r, entity);
        return r;
    }

    public List<ApplyItemResponse> toEnrichedResponseList(List<ApplyItem> entities) {
        return entities.stream().map(this::toEnrichedResponse).toList();
    }

    private void enrich(ApplyItemResponse r, ApplyItem entity) {
        if (entity.getTenderLot() == null || entity.getMedEquipment() == null || entity.getDistributor() == null) {
            return;
        }
        List<PriceRequestItem> found = priceRequestItemRepository.findResponseFor(
                entity.getTenderLot().getId(),
                entity.getMedEquipment().getId(),
                entity.getDistributor().getId()
        );
        if (found.isEmpty()) return;

        BigDecimal procurement = found.get(0).getResponsePrice();
        r.setProcurementCost(procurement);

        if (entity.getOfferedCost() != null) {
            BigDecimal margin = entity.getOfferedCost().subtract(procurement);
            r.setMargin(margin);
            if (procurement.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal pct = margin.multiply(BigDecimal.valueOf(100))
                        .divide(procurement, 2, RoundingMode.HALF_UP);
                r.setMarginPercent(pct);
            }
        }
    }
}
