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
    private BigDecimal procurementCost;  // закупочная цена у дистрибьютора (из последнего КП)
    private BigDecimal margin;            // маржа на единицу = offeredCost - procurementCost
    private BigDecimal marginPercent;     // процент маржинальности от закупки
}
