package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Одно поле поставщика сделки; value пусто — сброс суммы (по КП). */
@Data
public class SupplierFieldUpdate {
    @NotNull(message = "Не указано поле")
    private SupplierField field;
    @Size(max = 100, message = "Слишком длинное значение")
    private String value;
}
