package com.vladoose.nir.integration.skpharmacy;

import java.math.BigDecimal;

/** Строка списка объявлений СК-Фармации (searchanno). */
public record SkAnnounce(
        String announceId,   // числовой id из ссылки announce/index/{id}
        String numberAnno,   // «521464-1»
        String organizer,
        String nameRu,       // наименование (рус+каз одной строкой)
        String purchaseType, // «Тендер»
        String subjectType,  // «Товар»
        String acceptStart,  // «2026-07-13 09:00:00»
        String acceptEnd,
        Integer lotsCount,
        BigDecimal totalSum,
        String status        // «Опубликовано»
) {}
