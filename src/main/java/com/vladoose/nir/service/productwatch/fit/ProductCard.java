package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.RegStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Наш товар для сверки (спека §4): факты и стемы текста, статус РУ. */
public record ProductCard(long productId, String name, String brand, String slug, String regNumber, RegStatus regStatus,
                          LocalDate regExpires, boolean regUnlimited, List<ProductFact> facts, Set<String> stems) {
    public boolean regActive() { return regStatus == RegStatus.ACTIVE; }
}
