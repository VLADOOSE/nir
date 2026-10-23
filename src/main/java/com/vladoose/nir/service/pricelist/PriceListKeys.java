package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.PriceListItem;

import java.util.ArrayList;
import java.util.List;

/** Ключи товара строки прайса: «p<наш товар>», «e<позиция каталога>», «k<код модели>» — в этом порядке. */
public final class PriceListKeys {

    private PriceListKeys() {}

    public static List<String> of(PriceListItem r) {
        return of(r.getSiteProductId(), r.getMedEquipmentId(), r.getModelKey());
    }

    public static List<String> of(Long siteProductId, Long equipmentId, String modelKey) {
        List<String> out = new ArrayList<>(3);
        if (siteProductId != null) out.add("p" + siteProductId);
        if (equipmentId != null) out.add("e" + equipmentId);
        if (modelKey != null && !modelKey.isBlank()) out.add("k" + modelKey);
        return out;
    }

    /** Есть ли общий ключ. */
    public static boolean same(List<String> a, List<String> b) {
        for (String k : a) if (b.contains(k)) return true;
        return false;
    }
}
