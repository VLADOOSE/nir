package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Сумма наших лотов тендера (решение оператора 2026-10-20: порог «от 1 млн ₸»): цена за единицу × количество; лот в
 * тендере один и без цены — сумма тендера; ничего не известно — null (порог не доказан).
 */
public final class OurLots {
    private OurLots() {}

    public static BigDecimal sum(Tender t, Collection<Long> lotIds) {
        Set<Long> ids = new HashSet<>(lotIds);
        BigDecimal sum = null;
        for (TenderLot l : t.getLots()) {
            if (!ids.contains(l.getId()) || l.getMaxCost() == null || l.getQuantity() == null) continue;
            BigDecimal a = l.getMaxCost().multiply(BigDecimal.valueOf(l.getQuantity()));
            sum = sum == null ? a : sum.add(a);
        }
        if (sum == null && t.getLots().size() == 1 && ids.contains(t.getLots().get(0).getId())) return t.getTotalCost();
        return sum;
    }
}
