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
    /** Цена пришла в чужой валюте (V44): валюта, исходная цена и курс, по которому она пересчитана. */
    private String responseCurrency;
    private BigDecimal responseOriginal;
    private BigDecimal responseRate;
}
