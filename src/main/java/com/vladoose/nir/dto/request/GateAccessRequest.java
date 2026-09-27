package com.vladoose.nir.dto.request;

import lombok.Data;

/** «Запросить доступ» на калитке. Имя проверяет сервис (1–60 символов). */
@Data
public class GateAccessRequest {
    private String name;
}
