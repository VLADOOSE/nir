package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class PrivateRequestResponse {
    private Long id;
    private String number;
    private FacilityResponse client;
    private String status;
    private OffsetDateTime createdAt;
    private List<PrivateRequestLineResponse> lines;
}
