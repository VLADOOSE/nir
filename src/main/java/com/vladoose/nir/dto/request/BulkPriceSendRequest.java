package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

@Data
public class BulkPriceSendRequest {
    @NotNull private Long tenderId;
    @NotNull private Long distributorId;
    @NotEmpty private List<Item> items;

    @Data
    public static class Item {
        @NotNull private Long tenderLotId;
        @NotNull private Long medEquipmentId;
        @NotNull @Positive private Integer requestedQuantity;
    }
}
