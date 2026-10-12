package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PrivateRequestLineFieldUpdate {
    @NotNull(message = "Не указано поле позиции")
    private PrivateRequestLineField field;
    @Size(max = 10000, message = "Слишком длинное значение")
    private String value;
}
