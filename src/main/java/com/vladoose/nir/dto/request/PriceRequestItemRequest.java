package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PriceRequestItemRequest {
    private Long id;

    @NotNull
    private Long tenderLotId;

    @NotNull
    private Long medEquipmentId;

    @NotNull
    @Positive
    private Integer requestedQuantity;

    @PositiveOrZero
    private BigDecimal responsePrice;

    private String responseNote;
}
