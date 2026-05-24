package com.vladoose.nir.dto.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MedEquipmentResponse {
    private Long id;
    private String name;
    private String manufact;
    private EquipmentTypeResponse equipmentType;
    private Integer lengthMm;
    private Integer widthMm;
    private Integer heightMm;
    private BigDecimal weightKg;
    private String spec;
}
