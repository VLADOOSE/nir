package com.vladoose.nir.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
    private ProposedEquipmentResponse proposedEquipment;
    /** Лота больше нет на площадке; остался, потому что на него есть запрос КП или позиция заявки (B5). */
    private OffsetDateTime sourceRemovedAt;
}
