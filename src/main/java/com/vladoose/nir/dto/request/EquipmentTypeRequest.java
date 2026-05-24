package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EquipmentTypeRequest {
    @NotBlank(message = "Название обязательно")
    @Size(max = 100)
    private String name;
}
