package com.vladoose.nir.integration.ndda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Элемент комплектности МИ (RegisterService/MtComplectList) — живая форма закреплена NddaHttpClientTest. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NddaComplectItemDto {
    private String productName;
    private String component;
    private String producerName;
    private String countryName;
    private Integer partNumber;
}
