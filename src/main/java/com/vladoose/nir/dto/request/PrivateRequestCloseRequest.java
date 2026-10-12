package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.PrivateRequestStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PrivateRequestCloseRequest {
    @NotNull(message = "Укажите исход")
    private PrivateRequestStatus outcome;
}
