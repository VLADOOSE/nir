package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class PriceRequestSendRequest {

    @NotNull(message = "Не указан тендер")
    private Long tenderId;

    @NotEmpty(message = "Не выбраны поставщики")
    private List<Long> distributorIds;

    @NotEmpty(message = "Не выбраны позиции")
    @Valid
    private List<Item> items;

    private String subjectOverride; // человеческая часть темы; токен [КП-id] всегда добавляет сервер
    private String bodyOverride;    // если задан — уходит вместо скомпонованного тела

    @Data
    public static class Item {
        @NotNull(message = "Не указан лот")
        private Long tenderLotId;
        private Long medEquipmentId; // null = запрос по голому лоту
        @NotNull @Min(value = 1, message = "Количество должно быть не меньше 1")
        private Integer requestedQuantity;
    }
}
