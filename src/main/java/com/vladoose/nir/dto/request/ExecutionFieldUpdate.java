package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Одно поле исполнения; value пусто — сброс. Значение проверяет сервис (русский текст отказа). */
@Data
public class ExecutionFieldUpdate {
    @NotNull(message = "Не указано поле")
    private ExecutionField field;
    @Size(max = 10000, message = "Слишком длинное значение")
    private String value;
}
