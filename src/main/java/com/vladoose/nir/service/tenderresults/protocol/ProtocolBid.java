package com.vladoose.nir.service.tenderresults.protocol;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Участник протокола: место (у отклонённых — пусто), цена за единицу (конкурс / ОИ — из суммы и количества), сумма, время подачи. */
public record ProtocolBid(Integer place, String name, String bin, BigDecimal unitPrice, BigDecimal total, LocalDateTime submittedAt,
                          boolean rejected, String rejectReason) {}
