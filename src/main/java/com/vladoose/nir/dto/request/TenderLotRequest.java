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

    @Positive(message = "Максимальная длина должна быть положительной")
    private Integer maxLengthMm;

    @Positive(message = "Максимальная ширина должна быть положительной")
    private Integer maxWidthMm;

    @Positive(message = "Максимальная высота должна быть положительной")
    private Integer maxHeightMm;

    @Positive(message = "Максимальный вес должен быть положительным")
    private BigDecimal maxWeightKg;

    private String requiredSpec;
}
