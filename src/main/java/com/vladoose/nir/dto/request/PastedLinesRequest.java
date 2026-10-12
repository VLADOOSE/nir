package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PastedLinesRequest {
    @NotNull(message = "Нет текста")
    @Size(max = 100_000, message = "Слишком длинный текст — вставьте частями")
    private String text;
}
