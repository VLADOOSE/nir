package com.vladoose.nir.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Сверка «модели в работе» с ТЗ лота (спека 2026-10-20-lot-model-in-work §6). card: OK | MISSING | NONE. */
public record LotModelFitResponse(Model model, String note, String setBy, OffsetDateTime setAt, String card, String error,
                                  LotProductMatchDto.Candidate fit) {
    public record Model(Long equipmentId, String name, String manufact, String source, Long siteProductId, String url,
                        String regNumber, String regStatus, LocalDate regExpires, boolean regUnlimited) {}
}
