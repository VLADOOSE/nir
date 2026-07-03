package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class ProposedEquipmentResponse {
    private Long id;
    private String name;
    private String manufact;
    private String registrationStatus; // REGISTERED | NOT_FOUND | UNCHECKED
    private String regNumber;          // № РУ, если модель привязана к реестру
}
