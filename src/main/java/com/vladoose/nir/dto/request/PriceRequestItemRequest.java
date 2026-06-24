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

    // nullable: частная заявка называет бренд/модель, которого может не быть в нашем каталоге
    // (тендерный поток всё равно всегда передаёт medEquipmentId)
    private Long medEquipmentId;

    @NotNull
    @Positive
    private Integer requestedQuantity;

    @PositiveOrZero
    private BigDecimal responsePrice;

    private String responseNote;
}
