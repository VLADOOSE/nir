package com.vladoose.nir.integration.goszakup.dto;

import java.util.List;

/** Объявление для итогов (спека 2026-10-22-tender-results-market-prices §4): статус, время итогов, лоты, «Протокол итогов». */
public record ResultBuy(Long id, String numberAnno, Integer buyStatusId, String resultsAt, String supplierBin, Integer tradeMethodId,
                        String tradeMethodName, List<ResultLot> lots, List<ProtocolFileRef> protocols) {}
