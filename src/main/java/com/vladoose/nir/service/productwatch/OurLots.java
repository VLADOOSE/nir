package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Сумма наших лотов тендера (решение оператора 2026-10-20: порог «от 1 млн ₸»): цена за единицу × количество; лот в
 * тендере один и без цены — сумма тендера; ничего не известно — null (порог не доказан).
 */
public final class OurLots {
    private OurLots() {}

    public static BigDecimal sum(Tender t, Collection<Long> lotIds) {
        return sum(t, t.getLots(), lotIds);
    }

    /** То же по лотам тендера, загруженным отдельно: в потоке без сессии ленивую Tender.lots читать нельзя (CLAUDE.md §6). */
    public static BigDecimal sum(Tender t, List<TenderLot> tenderLots, Collection<Long> lotIds) {
        Set<Long> ids = new HashSet<>(lotIds);
        BigDecimal sum = null;
        for (TenderLot l : tenderLots) {
            if (!ids.contains(l.getId()) || l.getMaxCost() == null || l.getQuantity() == null) continue;
            BigDecimal a = l.getMaxCost().multiply(BigDecimal.valueOf(l.getQuantity()));
            sum = sum == null ? a : sum.add(a);
        }
        if (sum == null && tenderLots.size() == 1 && ids.contains(tenderLots.get(0).getId())) return t.getTotalCost();
        return sum;
    }
}
