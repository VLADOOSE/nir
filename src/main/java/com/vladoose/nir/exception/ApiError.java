package com.vladoose.nir.exception;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
public class ApiError {
    private int status;
    private String message;
    private Map<String, String> errors; // key = имя поля, value = текст ошибки
}
