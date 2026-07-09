package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.OfferComparisonResponse;
import com.vladoose.nir.dto.response.OfferComparisonResponse.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.PriceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** Сводка предложений по тендеру: матрица лоты×поставщики, мин. цена по лоту, итоги. Read-only. */
@Service
public class OfferComparisonService {

    private final TenderService tenderService;
    private final PriceRequestRepository priceRequestRepository;

    public OfferComparisonService(TenderService tenderService, PriceRequestRepository priceRequestRepository) {
        this.tenderService = tenderService;
        this.priceRequestRepository = priceRequestRepository;
    }

    @Transactional(readOnly = true)
    public OfferComparisonResponse build(Long tenderId) {
        Tender tender = tenderService.findById(tenderId); // em.find обходит фильтр рынка
        if (tender.getMarket() != null && tender.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Тендер не найден: id=" + tenderId);
        }

        List<PriceRequest> prs = priceRequestRepository.findByTenderId(tenderId);

        List<Supplier> suppliers = new ArrayList<>();
        List<Cell> cells = new ArrayList<>();
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        LinkedHashMap<Long, Lot> lotsById = new LinkedHashMap<>();

        for (PriceRequest pr : prs) {
            boolean anyPriced = false;
            BigDecimal total = BigDecimal.ZERO;
            for (PriceRequestItem it : pr.getItems()) {
                if (it.getResponsePrice() == null || it.getTenderLot() == null) continue;
                anyPriced = true;
                TenderLot lot = it.getTenderLot();
                int qty = it.getRequestedQuantity() != null ? it.getRequestedQuantity() : 1;
                lotsById.putIfAbsent(lot.getId(),
                        new Lot(lot.getId(), lot.getLotNumber(), lot.getEquipName(), it.getRequestedQuantity()));
                cells.add(new Cell(lot.getId(), pr.getId(), it.getResponsePrice(), it.getRequestedQuantity()));
                total = total.add(it.getResponsePrice().multiply(BigDecimal.valueOf(qty)));
            }
            if (anyPriced) {
                suppliers.add(new Supplier(pr.getId(), pr.getDistributor().getName(), pr.getStatus()));
                totals.put(pr.getId(), total);
            }
        }

        Map<Long, Long> bestByLot = new LinkedHashMap<>();
        Map<Long, BigDecimal> bestPriceByLot = new HashMap<>();
        for (Cell c : cells) {
            BigDecimal cur = bestPriceByLot.get(c.getLotId());
            int cmp = cur == null ? -1 : c.getResponsePrice().compareTo(cur);
            if (cmp < 0 || (cmp == 0 && c.getPriceRequestId() < bestByLot.get(c.getLotId()))) {
                bestPriceByLot.put(c.getLotId(), c.getResponsePrice());
                bestByLot.put(c.getLotId(), c.getPriceRequestId());
            }
        }

        return new OfferComparisonResponse(new ArrayList<>(lotsById.values()), suppliers, cells, bestByLot, totals);
    }
}
