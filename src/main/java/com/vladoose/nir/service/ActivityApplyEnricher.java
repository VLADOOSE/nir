package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.ActivityApplyResponse;
import com.vladoose.nir.dto.response.ApplyItemResponse;
import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.mapper.ActivityApplyMapper;
import com.vladoose.nir.repository.ApplyItemRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Считает агрегаты выручки/закупки/прибыли по позициям заявки.
 * Прибыль считается только по тем позициям, где известна закупочная цена (есть отклик КП).
 */
@Component
public class ActivityApplyEnricher {

    private final ActivityApplyMapper mapper;
    private final ApplyItemRepository applyItemRepository;
    private final ApplyItemEnricher applyItemEnricher;

    public ActivityApplyEnricher(ActivityApplyMapper mapper,
                                  ApplyItemRepository applyItemRepository,
                                  ApplyItemEnricher applyItemEnricher) {
        this.mapper = mapper;
        this.applyItemRepository = applyItemRepository;
        this.applyItemEnricher = applyItemEnricher;
    }

    public ActivityApplyResponse toEnrichedResponse(ActivityApply entity) {
        ActivityApplyResponse r = mapper.toResponse(entity);
        enrich(r, entity.getId());
        return r;
    }

    public List<ActivityApplyResponse> toEnrichedResponseList(List<ActivityApply> entities) {
        return entities.stream().map(this::toEnrichedResponse).toList();
    }

    private void enrich(ActivityApplyResponse r, Long applyId) {
        List<ApplyItemResponse> items = applyItemEnricher.toEnrichedResponseList(
                applyItemRepository.findByApplyId(applyId)
        );

        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal procurement = BigDecimal.ZERO;
        boolean anyProcurement = false;

        for (ApplyItemResponse it : items) {
            int qty = it.getQuantity() != null ? it.getQuantity() : 1;
            if (it.getOfferedCost() != null) {
                revenue = revenue.add(it.getOfferedCost().multiply(BigDecimal.valueOf(qty)));
            }
            if (it.getProcurementCost() != null) {
                procurement = procurement.add(it.getProcurementCost().multiply(BigDecimal.valueOf(qty)));
                anyProcurement = true;
            }
        }

        r.setTotalRevenue(revenue);
        if (anyProcurement) {
            r.setTotalProcurement(procurement);
            BigDecimal profit = revenue.subtract(procurement);
            r.setTotalProfit(profit);
            if (procurement.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal pct = profit.multiply(BigDecimal.valueOf(100))
                        .divide(procurement, 2, RoundingMode.HALF_UP);
                r.setMarginPercent(pct);
            }
        }
    }
}
