package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ApplyItemRequest {

    private Long applyId;
    private Long tenderLotId;
    private Long medEquipId;
    private Long distributorId;

    @Positive(message = "Предложенная цена должна быть положительной")
    private BigDecimal offeredCost;

    @NotNull(message = "Количество обязательно")
    @Positive(message = "Количество должно быть положительным")
    private Integer quantity;
}
