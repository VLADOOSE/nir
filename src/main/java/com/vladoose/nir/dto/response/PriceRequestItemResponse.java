package com.vladoose.nir.dto.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PriceRequestItemResponse {
    private Long id;
    private TenderLotShortResponse tenderLot;
    private MedEquipmentResponse medEquipment;
    private Integer requestedQuantity;
    private BigDecimal responsePrice;
    private String responseNote;
    /** 💡 — цена из разбора и ещё не сверена. */
    private boolean responseAuto;
    private Long responseFileId;
    private Integer responseFileRow;
}
