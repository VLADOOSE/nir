package com.vladoose.nir.service.pricelist;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Строка «закупка по прайсу ≈ …» в пачке бота и «🏥 Новый тендер» (спека 2026-10-23 §6.2 п. 4). */
public interface PriceLines {

    /** tenderId → строка по нашим лотам тендера; ни у одного лота нет цены — без строки. */
    Map<Long, String> lines(Map<Long, List<Long>> lotIdsByTender);

    /** Рядом со строкой «рынок»: «рынок: … · закупка по прайсу ≈ …»; рынка нет — только закупка. */
    static Map<Long, String> merge(Map<Long, String> market, Map<Long, String> price) {
        Map<Long, String> out = new LinkedHashMap<>(market);
        price.forEach((id, line) -> out.merge(id, line, (a, b) -> a + " · " + b));
        return out;
    }
}
