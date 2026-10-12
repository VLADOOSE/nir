package com.vladoose.nir.service.offer;

import java.util.*;

/**
 * Цена закупки по умолчанию (спека 2026-10-12 §8, решение плана 2). Кандидаты — строки ответов с ценой у запросов не
 * DECLINED / REJECTED / CLOSED; у каждого поставщика — только последняя (как ячейка таблицы цен заявки), иначе цена,
 * которую он потом поменял новым ответом, могла бы стать «лучшей». Лучшая — самая дешёвая среди принятых (ACCEPTED),
 * иначе самая дешёвая; равенство — более ранний запрос.
 */
public final class PurchaseOffers {

    public static final Set<String> EXCLUDED = Set.of("DECLINED", "REJECTED", "CLOSED");
    private static final Comparator<PurchaseOffer> CHEAPEST =
            Comparator.comparing(PurchaseOffer::price).thenComparingLong(PurchaseOffer::priceRequestId);

    private PurchaseOffers() {}

    /** Кандидаты позиции: без исключённых статусов, по последней строке каждого поставщика, от дешёвой. */
    public static List<PurchaseOffer> current(Collection<PurchaseOffer> lines) {
        Map<Long, PurchaseOffer> latest = new HashMap<>();
        for (PurchaseOffer o : lines) {
            if (o.price() == null || EXCLUDED.contains(o.status())) continue;
            latest.merge(o.distributorId(), o, (a, b) -> a.priceRequestId() >= b.priceRequestId() ? a : b);
        }
        return latest.values().stream().sorted(CHEAPEST).toList();
    }

    public static Optional<PurchaseOffer> best(List<PurchaseOffer> current) {
        Optional<PurchaseOffer> accepted = current.stream().filter(o -> "ACCEPTED".equals(o.status())).min(CHEAPEST);
        return accepted.isPresent() ? accepted : current.stream().min(CHEAPEST);
    }
}
