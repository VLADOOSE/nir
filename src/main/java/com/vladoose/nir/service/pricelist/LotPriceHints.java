package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.dto.response.PriceHint;
import com.vladoose.nir.dto.response.PriceHintRow;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.TenderLotProductMatchRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.tenderresults.ResultTexts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Подсказки лотов тендера (спека 2026-10-23 §6.2 п. 3–4): товар лота — модель в работе, иначе товар сверки («подходит»,
 * «подходит, РУ истекло», «◐ похоже» с ✓ > 0), иначе товар отметки NAMED / COPIED; лот без товара — без подсказки.
 */
@Service
public class LotPriceHints implements PriceLines {

    private final PriceHints hints;
    private final TenderLotRepository lots;
    private final TenderLotProductMatchRepository matches;

    public LotPriceHints(PriceHints hints, TenderLotRepository lots, TenderLotProductMatchRepository matches) {
        this.hints = hints;
        this.lots = lots;
        this.matches = matches;
    }

    public static PriceHints.Target target(TenderLot lot, List<TenderLotProductMatch> ms) {
        MedEquipment e = lot.getProposedEquipment();
        if (e != null) return new PriceHints.Target(null, null, e.getId(), null);
        Long fit = ms.stream().filter(m -> m.getFitProductId() != null && (m.getFit() == FitVerdict.FITS
                        || m.getFit() == FitVerdict.FITS_NO_RU || (m.getFit() == FitVerdict.PARTIAL && m.getFitMet() != null && m.getFitMet() > 0)))
                .map(TenderLotProductMatch::getFitProductId).findFirst().orElse(null);
        if (fit != null) return new PriceHints.Target(null, fit, null, null);
        Long named = ms.stream().filter(m -> m.getProductId() != null
                        && (m.getLevel() == ProductMatchLevel.NAMED || m.getLevel() == ProductMatchLevel.COPIED))
                .map(TenderLotProductMatch::getProductId).findFirst().orElse(null);
        return named == null ? null : new PriceHints.Target(null, named, null, null);
    }

    /** Закупка лучшей строки в валюте рынка × 100 / цена лота за единицу; нет чего-то — null. */
    public static Integer budgetPct(PriceHint h, BigDecimal maxCost) {
        if (h == null || h.rows().isEmpty() || maxCost == null || maxCost.signum() <= 0) return null;
        BigDecimal c = h.rows().get(0).converted();
        return c == null ? null : c.multiply(BigDecimal.valueOf(100)).divide(maxCost, 0, RoundingMode.HALF_UP).intValue();
    }

    @Transactional(readOnly = true)
    public Map<Long, PriceHint> forLots(Market m, List<TenderLot> lotList) {
        Map<Long, List<TenderLotProductMatch>> byLot = new HashMap<>();
        Set<Long> tenderIds = new HashSet<>();
        lotList.forEach(l -> tenderIds.add(l.getTender().getId()));
        if (!tenderIds.isEmpty()) {
            for (TenderLotProductMatch x : matches.findByTenderIdIn(tenderIds)) byLot.computeIfAbsent(x.getLotId(), k -> new ArrayList<>()).add(x);
        }
        List<Long> ids = new ArrayList<>();
        List<PriceHints.Target> targets = new ArrayList<>();
        for (TenderLot l : lotList) {
            PriceHints.Target t = target(l, byLot.getOrDefault(l.getId(), List.of()));
            if (t == null) continue;
            ids.add(l.getId());
            targets.add(t);
        }
        Map<Long, PriceHint> out = new HashMap<>();
        List<PriceHint> found = hints.hints(m, targets);
        for (int i = 0; i < ids.size(); i++) out.put(ids.get(i), found.get(i));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> lines(Map<Long, List<Long>> lotIdsByTender) {
        Set<Long> all = new HashSet<>();
        lotIdsByTender.values().forEach(all::addAll);
        if (all.isEmpty()) return Map.of();
        Map<Long, TenderLot> byId = new HashMap<>();
        lots.findAllById(all).forEach(l -> byId.put(l.getId(), l));
        Map<Long, PriceHint> found = forLots(Market.KZ, new ArrayList<>(byId.values()));
        Map<Long, String> out = new LinkedHashMap<>();
        lotIdsByTender.forEach((tenderId, ids) -> {
            int ours = 0, priced = 0;
            BigDecimal sum = BigDecimal.ZERO;
            for (Long id : ids) {
                if (!found.containsKey(id)) continue;   // лот без нашего товара — не «наш»
                ours++;
                TenderLot l = byId.get(id);
                PriceHint h = found.get(id);
                BigDecimal c = h.rows().isEmpty() ? null : h.rows().get(0).converted();
                if (c == null || l.getQuantity() == null || l.getQuantity() <= 0) continue;
                priced++;
                sum = sum.add(c.multiply(BigDecimal.valueOf(l.getQuantity())));
            }
            if (priced == 0) return;
            out.put(tenderId, "закупка по прайсу ≈ " + ResultTexts.money(sum)
                    + (priced < ours ? " (" + priced + " из " + ours + " " + (ours % 10 == 1 && ours % 100 != 11 ? "лота" : "лотов") + ")" : ""));
        });
        return out;
    }
}
