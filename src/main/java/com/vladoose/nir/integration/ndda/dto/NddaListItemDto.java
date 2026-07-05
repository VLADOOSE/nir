package com.vladoose.nir.integration.ndda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Элемент ответа RegisterService/list (используем только id; живая форма — NddaHttpClientTest). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NddaListItemDto {
    private Long id;
    @JsonProperty("reg_number")
    private String regNumber;
}
