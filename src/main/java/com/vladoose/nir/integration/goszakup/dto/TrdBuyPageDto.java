package com.vladoose.nir.integration.goszakup.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrdBuyPageDto {
    private List<TrdBuyDto> items;
    @JsonProperty("next_page") private String nextPage;
    private Integer total;
}
