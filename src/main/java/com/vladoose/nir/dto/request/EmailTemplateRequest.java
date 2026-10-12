package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EmailTemplateRequest {
    @NotBlank(message = "Тема не может быть пустой")
    private String subject;
    @NotBlank(message = "Текст письма не может быть пустым")
    private String body;

    /** Тексты запроса на бланке (спека 2026-10-12 §6.4): null — не прислано (не меняется), пусто — зашитый текст. */
    @Size(max = 500, message = "Заголовок — не больше 500 символов")
    private String rfqTitle;
    @Size(max = 2000, message = "Вводная — не больше 2000 символов")
    private String rfqIntro;
    @Size(max = 4000, message = "Пункты — не больше 4000 символов")
    private String rfqAsk;
    @Size(max = 1000, message = "Строка о заказчике — не больше 1000 символов")
    private String rfqClientLine;
}
