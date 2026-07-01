package com.vladoose.nir.integration.goszakup.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LotDto {
    @JsonProperty("lot_number") private String lotNumber;
    @JsonProperty("name_ru") private String nameRu;
    /** Техспека лота (полное описание ТЗ). */
    @JsonProperty("description_ru") private String descriptionRu;
    private BigDecimal amount;
    private Integer count;
    @JsonProperty("trd_buy_number_anno") private String trdBuyNumberAnno;
}
