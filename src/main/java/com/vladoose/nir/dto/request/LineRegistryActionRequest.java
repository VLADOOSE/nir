package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LineRegistryActionRequest {
    @NotNull(message = "Не указано действие")
    private LineRegistryAction action;
    /** у CONFIRM — выбранная запись реестра; пусто — подтвердить текущую подсказку */
    @Size(max = 100)
    private String regNumber;
}
