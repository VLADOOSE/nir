package com.vladoose.nir.integration.goszakup.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubjectDto {
    private String bin;
    @JsonProperty("name_ru") private String nameRu;
    /** Живой /v2/subject/biin/{биин}: адреса — массив объектов (КАТО и строка адреса внутри). */
    private List<SubjectAddressDto> address;

    public String firstAddress() {
        return address != null && !address.isEmpty() ? address.get(0).getAddress() : null;
    }

    public String firstKato() {
        return address != null && !address.isEmpty() ? address.get(0).getKatoCode() : null;
    }
}
