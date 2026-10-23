package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.PriceList;

import java.time.LocalDate;
import java.util.Comparator;

/** Какой прайс новее (спека §5.5): дата прайса, а нет её — дата загрузки; при равенстве — больший id. */
public final class PriceListOrder {

    private PriceListOrder() {}

    public static LocalDate date(PriceList p) {
        return p.getListDate() != null ? p.getListDate() : p.getCreatedAt() == null ? LocalDate.MIN : p.getCreatedAt().toLocalDate();
    }

    /** Новые первыми. */
    public static final Comparator<PriceList> NEWER = Comparator.comparing(PriceListOrder::date)
            .thenComparing(PriceList::getId, Comparator.nullsFirst(Comparator.naturalOrder())).reversed();
}
