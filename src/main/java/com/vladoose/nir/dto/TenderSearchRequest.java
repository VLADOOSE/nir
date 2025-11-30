package com.vladoose.nir.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenderSearchRequest {
    private String tndrId;
    private String organization;
    private Integer lotCount;
    private Double costFrom;
    private Double costTo;
    private String stage;
    private String dateFrom;
    private String dateTo;
    private Integer limit;

    // Lot-specific
    private String lotNo;
    private String lotMiNo;
    private String lotName;
    private String lotDesc;
    private Integer lotCountExact;
}
