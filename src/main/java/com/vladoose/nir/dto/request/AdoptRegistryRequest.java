package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AdoptRegistryRequest {
    @NotBlank(message = "Не указан номер РУ")
    private String regNumber;
}
