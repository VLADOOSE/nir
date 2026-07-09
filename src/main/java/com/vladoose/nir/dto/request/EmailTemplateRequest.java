package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EmailTemplateRequest {
    @NotBlank(message = "Тема не может быть пустой")
    private String subject;
    @NotBlank(message = "Текст письма не может быть пустым")
    private String body;
}
