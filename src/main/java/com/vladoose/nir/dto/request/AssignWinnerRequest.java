package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AssignWinnerRequest {
    @NotNull(message = "lotId обязателен")
    private Long lotId;
    @NotNull(message = "priceRequestId обязателен")
    private Long priceRequestId;
    private Double markupPercent;   // опционально; null → 0 (offeredCost = закупка)
}
