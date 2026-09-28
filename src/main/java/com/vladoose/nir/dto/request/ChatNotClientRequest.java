package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChatNotClientRequest {
    @NotNull
    private Boolean value;
}
