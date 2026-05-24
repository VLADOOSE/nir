package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TenderLotRequest {

    private Long tenderId;

    private Integer lotNumber;

    @NotBlank(message = "Название оборудования обязательно")
    private String equipName;

    private Long equipTypeId;

    @NotNull(message = "Количество обязательно")
    @Positive(message = "Количество должно быть положительным")
    private Integer quantity;

    @Positive(message = "Максимальная цена должна быть положительной")
    private BigDecimal maxCost;

    private Integer maxLengthMm;
    private Integer maxWidthMm;
    private Integer maxHeightMm;
    private BigDecimal maxWeightKg;

    private String requiredSpec;
}
