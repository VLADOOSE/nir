package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class EquipmentRegistrationResponse {
    private String status;            // RegistrationStatus.name()
    private boolean vatExempt;        // true только для REGISTERED
    private String regNumber;
    private String producer;
    private String country;
    private LocalDate regDate;
    private LocalDate expirationDate;
    private Boolean unlimited;
    private OffsetDateTime checkedAt;
}
