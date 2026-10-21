package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiAnswerRequest {
    @NotBlank(message = "Вставьте ответ ИИ — блок «Для АИС»")
    @Size(max = 20000, message = "Ответ длиннее 20 000 знаков — вставьте только блок «Для АИС»")
    private String text;
}
