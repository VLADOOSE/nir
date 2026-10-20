package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Строка «Найти тендеры» (спека §11.2). kind — TENDER / PLAN; state — OPEN «приём до …», FAILED «не состоялся»,
 * CLOSED «приём закрыт», DONE «состоялся», CANCELLED «отменён», PLANNED «план: месяц год».
 */
public record TenderSearchHit(String kind, String numberAnno, String lotNumber, String lotName, String announcement,
                              String customer, String region, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount,
                              String deadlineAt, String state, String tradeMethod, double score, List<Integer> positions,
                              List<Integer> specPositions, Long tenderId, Integer planYear, Integer planMonth) {}
