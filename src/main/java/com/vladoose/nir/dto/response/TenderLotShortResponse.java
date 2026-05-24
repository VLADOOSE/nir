package com.vladoose.nir.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Slim lot projection used in nested references (without parent tender),
 * to avoid recursion when serializing TenderResponse.lots,
 * ApplyItemResponse.tenderLot, PriceRequestResponse.tenderLot.
 */
@Data
public class TenderLotShortResponse {
    private Long id;
    private Integer lotNumber;
    private String equipName;
    private EquipmentTypeResponse equipmentType;
    private Integer quantity;
    private BigDecimal maxCost;
}
