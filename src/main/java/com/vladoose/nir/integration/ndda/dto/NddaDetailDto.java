package com.vladoose.nir.integration.ndda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Карточка РУ (RegisterService/MtMainGetById) — только поля описания; живая форма — NddaHttpClientTest. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NddaDetailDto {
    private Long id;
    private String regNumber;
    private String purpose;
    private String useArea;
    private String degreeRiskName;
    private String shortTechnicalCharacteristicsRu;
    @JsonProperty("termName_rus")
    private String termNameRus;
    private String termDefinition;
}
