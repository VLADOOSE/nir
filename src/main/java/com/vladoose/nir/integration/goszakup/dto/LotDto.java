package com.vladoose.nir.integration.goszakup.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LotDto {
    @JsonProperty("lot_number") private String lotNumber;
    @JsonProperty("name_ru") private String nameRu;
    /** Техспека лота (полное описание ТЗ). */
    @JsonProperty("description_ru") private String descriptionRu;
    private BigDecimal amount;
    /** Количество лота: площадка присылает и дробное («0.5») — в {@code Integer} Jackson молча делал 0. */
    private BigDecimal count;
    @JsonProperty("trd_buy_number_anno") private String trdBuyNumberAnno;
    /** id лота площадки — ключ строк договора (спека 2026-10-22 §12). */
    private Long id;
    /** Пункты плана лота — общие у всех попыток закупки. */
    @JsonProperty("point_list") private List<Long> pointList;
}
