package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Одно изменённое поле шапки; value пустое — поле очищается. */
@Data
public class PrivateRequestFieldUpdate {
    @NotNull(message = "Не указано поле")
    private PrivateRequestField field;
    @Size(max = 10000, message = "Слишком длинное значение")
    private String value;
}
