package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.WorkStage;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/** Стадия работы по тендеру (воронка), вычисляемая пакетно из существующих данных. */
@Service
public class TenderWorkStageService {

    private final PriceRequestRepository priceRequestRepository;
    private final ActivityApplyRepository applyRepository;

    public TenderWorkStageService(PriceRequestRepository priceRequestRepository,
                                  ActivityApplyRepository applyRepository) {
        this.priceRequestRepository = priceRequestRepository;
        this.applyRepository = applyRepository;
    }

    /** tenderId → стадия; только затронутые тендеры (отсутствие = NOT_STARTED). */
    @Transactional(readOnly = true)
    public Map<Long, WorkStage> stagesForMarket() {
        Market market = MarketContext.get();
        Map<Long, WorkStage> map = new HashMap<>();
        // порядок = монотонность: старшая стадия перекрывает младшую
        for (Long id : priceRequestRepository.findTenderIdsWithPriceRequest(market)) map.put(id, WorkStage.REQUESTED);
        for (Long id : priceRequestRepository.findTenderIdsWithResponsePrice(market)) map.put(id, WorkStage.PRICED);
        for (Long id : applyRepository.findTenderIdsWithApplyItems(market)) map.put(id, WorkStage.WINNER_SELECTED);
        return map;
    }
}
