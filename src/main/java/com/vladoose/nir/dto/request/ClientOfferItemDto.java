package com.vladoose.nir.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vladoose.nir.entity.ClientOfferItemKind;
import com.vladoose.nir.entity.OfferRegistrationStatus;
import com.vladoose.nir.service.offer.ItemCalc;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Строка КП: в запросе — ввод оператора, в ответе — плюс calc. Пустое наименование допустимо (оператор печатает); вид
 * строки обязателен (проверяет сервис — с номером строки в тексте ошибки). Связей волны 2 (поставщик, лот, строка ответа,
 * позиция каталога) здесь нет: PUT волны 1 их не принимает и не меняет.
 */
@Data
public class ClientOfferItemDto {
    private Long id;
    @Size(max = 64) private String key;
    private Integer lineNo;
    private ClientOfferItemKind kind;
    @Size(max = 4000) private String name;
    @Size(max = 255) private String model;
    @Size(max = 500) private String manufacturer;
    @Size(max = 200) private String country;
    @Size(max = 30) private String unit;
    private BigDecimal quantity;
    private BigDecimal purchasePrice;
    private Boolean purchaseVatSame;
    private BigDecimal purchaseVatRate;
    @Size(max = 255) private String supplierName;
    private BigDecimal markupPct;
    private BigDecimal priceOverride;
    private BigDecimal vatRate;
    private OfferRegistrationStatus registrationStatus;
    @Size(max = 1000) private String registrationText;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String regNumber;
    @Size(max = 4000) private String note;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private ItemCalc calc;
}
