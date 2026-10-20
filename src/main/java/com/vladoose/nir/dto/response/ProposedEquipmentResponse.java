package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class ProposedEquipmentResponse {
    private Long id;
    private String name;
    private String manufact;
    private String registrationStatus; // REGISTERED | NOT_FOUND | UNCHECKED
    private String regNumber;          // № РУ, если модель привязана к реестру
    private Long siteProductId;        // наш товар сайта (V40)
    private String productUrl;         // ссылка на товар сайта (ставит LotModelViews)
    private String source;             // SITE | REGISTRY | CATALOG
    private java.time.LocalDate regExpires;
    private Boolean regActive;         // РУ действует (ставит LotModelViews)
}
