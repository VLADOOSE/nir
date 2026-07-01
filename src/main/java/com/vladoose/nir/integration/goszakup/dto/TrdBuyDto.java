package com.vladoose.nir.integration.goszakup.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrdBuyDto {
    private Long id;
    @JsonProperty("number_anno") private String numberAnno;
    @JsonProperty("name_ru") private String nameRu;
    @JsonProperty("total_sum") private BigDecimal totalSum;
    @JsonProperty("count_lots") private Integer countLots;
    @JsonProperty("ref_buy_status_id") private Integer refBuyStatusId;
    @JsonProperty("customer_bin") private String customerBin;
    @JsonProperty("org_bin") private String orgBin;
    @JsonProperty("publish_date") private String publishDate;
    @JsonProperty("start_date") private String startDate;
    @JsonProperty("end_date") private String endDate;
    @JsonProperty("system_id") private Integer systemId;

    /** Живой /v2/trd-buy отдаёт только org_bin; customer_bin (если вдруг есть) — приоритетнее. */
    public String effectiveBin() {
        return customerBin != null && !customerBin.isBlank() ? customerBin : orgBin;
    }
}
