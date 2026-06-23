package com.vladoose.nir.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class ReconciliationRowResponse {
    private Long equipmentId;
    private String equipmentName;
    private String manufact;
    private String equipTypeName;
    private String status;           // RegistrationStatus.name()
    private boolean vatExempt;
    private String currentRegNumber; // привязанный № РУ, если есть
    private List<RegistryCandidateResponse> candidates;
}
