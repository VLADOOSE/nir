package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/** Полный итог лота (спека §11, §13): участники, договор, цепочка попыток, протокол. outcome пусто — итоги не проверялись. */
public record LotResultView(Long lotId, String outcome, String text, String lotStatus, String failReason, String failText,
                            LocalDateTime resultsAt, String protocolUrl, String protocolName, String protocolStatus, String protocolNote,
                            Integer bidsCount, Integer rejectedCount, List<Bid> bids, Contract contract, List<Attempt> attempts,
                            Long nextTenderId, String nextTenderNumber, OffsetDateTime checkedAt, OffsetDateTime finalAt) {

    public record Bid(Integer place, String name, String bin, BigDecimal unitPrice, BigDecimal total, LocalDateTime submittedAt,
                      boolean rejected, String rejectReason, boolean winner, boolean second, boolean ours) {}

    public record Contract(String number, LocalDate signedAt, BigDecimal unitPrice, BigDecimal sum, String supplierBin, String supplierName) {}

    public record Attempt(String anno, String lotNumber, String method, String methodName, String status, Integer statusId,
                          String publishedAt, String resultsAt, String supplierBin, String supplierName, Long tenderId, boolean own) {}
}
