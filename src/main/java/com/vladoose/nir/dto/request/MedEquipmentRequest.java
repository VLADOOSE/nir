package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class MedEquipmentRequest {

    @NotBlank(message = "Наименование обязательно")
    @Size(max = 255)
    private String name;

    @NotBlank(message = "Производитель обязателен")
    @Size(max = 255)
    private String manufact;

    private Long equipTypeId;

    @Positive(message = "Длина должна быть положительной")
    private Integer lengthMm;

    @Positive(message = "Ширина должна быть положительной")
    private Integer widthMm;

    @Positive(message = "Высота должна быть положительной")
    private Integer heightMm;

    @Positive(message = "Вес должен быть положительным")
    private BigDecimal weightKg;

    private String spec;
}
