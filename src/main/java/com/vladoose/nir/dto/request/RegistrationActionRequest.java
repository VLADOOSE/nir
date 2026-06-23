package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RegistrationActionRequest {
    @NotNull(message = "action обязателен")
    private RegistrationAction action;
    private String regNumber; // обязателен только для CONFIRM
}
