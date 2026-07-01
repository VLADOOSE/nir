package com.vladoose.nir.integration.goszakup.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Элемент массива address из /v2/subject/biin/{биин}. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubjectAddressDto {
    private String address;
    @JsonProperty("kato_code") private String katoCode;
    @JsonProperty("address_type") private String addressType;
}
