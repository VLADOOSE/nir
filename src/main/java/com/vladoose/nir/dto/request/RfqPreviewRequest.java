package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class RfqPreviewRequest {
    @NotNull(message = "Не указан поставщик")
    private Long distributorId;
    @NotEmpty(message = "Не выбраны позиции")
    private List<Long> lotIds;
    private LocalDate replyBy;
}
