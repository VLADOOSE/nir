package com.vladoose.nir.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Платёж: distributorId пусто — клиента. Проверки — в сервисе (решение при планировании 9). */
@Data
public class DealPaymentRequest {
    private Long distributorId;
    private BigDecimal amount;
    private LocalDate paidOn;
    private String note;
}
