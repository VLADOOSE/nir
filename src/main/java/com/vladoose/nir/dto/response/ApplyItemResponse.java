package com.vladoose.nir.dto.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ApplyItemResponse {
    private Long id;
    private ApplyShortResponse apply;
    private TenderLotShortResponse tenderLot;
    private MedEquipmentResponse medEquipment;
    private DistributorResponse distributor;
    private BigDecimal offeredCost;
    private Integer quantity;
}
