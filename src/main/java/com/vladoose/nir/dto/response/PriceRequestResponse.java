package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class PriceRequestResponse {
    private Long id;
    private TenderShortResponse tender;
    private DistributorResponse distributor;
    private String status;
    private OffsetDateTime sentAt;
    private LocalDate responseDate;
    private String note;
    private OffsetDateTime createdAt;
    private List<PriceRequestItemResponse> items;
}
