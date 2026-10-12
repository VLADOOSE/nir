package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** «Отправить N писем»: каждому поставщику — одно письмо только с его позициями. */
@Data
public class RfqSendRequest {
    private LocalDate replyBy;
    @NotEmpty(message = "Не выбраны поставщики")
    @Valid
    private List<Supplier> suppliers;

    @Data
    public static class Supplier {
        @NotNull(message = "Не указан поставщик")
        private Long distributorId;
        @NotEmpty(message = "Не выбраны позиции")
        private List<Long> lotIds;
    }
}
