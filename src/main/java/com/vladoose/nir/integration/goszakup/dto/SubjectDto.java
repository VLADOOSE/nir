package com.vladoose.nir.integration.goszakup.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubjectDto {
    private String bin;
    @JsonProperty("name_ru") private String nameRu;
    // имена полей региона уточняются на Task 9 (пробой схемы токеном):
    @JsonProperty("ref_kato_id") private String katoId;
    @JsonProperty("full_delivery_address") private String address;
}
