package com.vladoose.nir.integration.ndda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Элемент ответа RegisterService/list: id, № РУ и поля проверки РУ товаров сайта (живая форма — NddaHttpClientTest, NddaRegistrationClientTest). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NddaListItemDto {
    private Long id;
    @JsonProperty("reg_number")
    private String regNumber;
    private String name;
    private String producerNameRu;
    private String countryNameRu;
    @JsonProperty("reg_date")
    private String regDate;
    @JsonProperty("expiration_date")
    private String expirationDate;
    @JsonProperty("unlimited_sign")
    private Boolean unlimitedSign;
}
