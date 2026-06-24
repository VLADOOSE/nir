package com.vladoose.nir.dto.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TenderLotResponse {
    private Long id;
    private TenderShortResponse tender;
    private Integer lotNumber;
    private String equipName;
    private String manufact;
    private EquipmentTypeResponse equipmentType;
    private Integer quantity;
    private BigDecimal maxCost;
    private Integer maxLengthMm;
    private Integer maxWidthMm;
    private Integer maxHeightMm;
    private BigDecimal maxWeightKg;
    private String requiredSpec;
}
