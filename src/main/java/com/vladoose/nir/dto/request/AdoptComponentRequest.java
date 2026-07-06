package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Тело POST /api/lots/{id}/adopt-component: РУ аппарата + номер компонента в комплектности. */
@Data
public class AdoptComponentRequest {
    @NotBlank
    private String regNumber;
    @NotNull
    private Integer partNumber;
}
