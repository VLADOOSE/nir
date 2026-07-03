package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProposedEquipmentRequest {
    @NotNull(message = "Не указано оборудование")
    private Long equipmentId;
}
