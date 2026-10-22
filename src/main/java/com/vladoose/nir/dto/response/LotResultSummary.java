package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Итог лота строкой у лота (спека 2026-10-22-tender-results-market-prices §11): outcome — PENDING / DONE / FAILED / CANCELLED. */
public record LotResultSummary(String outcome, String text, String failReason, Integer bidsCount, String winnerName,
                               BigDecimal winnerPrice, Integer ourPlace, Boolean ourRejected, boolean contract, String protocolStatus,
                               LocalDateTime resultsAt) {}
