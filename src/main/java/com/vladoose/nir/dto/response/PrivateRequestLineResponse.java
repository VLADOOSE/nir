package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class PrivateRequestLineResponse {
    private Long lotId;
    private String name;
    private String manufact;
    private Integer quantity;
    private String registrationStatus;             // REGISTERED | NOT_FOUND
    private RegistryCandidateResponse topCandidate; // лучший кандидат реестра или null
}
