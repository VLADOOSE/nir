package com.vladoose.nir.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class TenderResponse {
    private Long id;
    private String tenderNumber;
    private FacilityResponse facility;
    private String status;
    private String purchaseType;
    private LocalDate deadline;
    private LocalDate publishDate;
    private BigDecimal totalCost;
    private String currency;
    private String source;
    private String description;
    private String deliveryAddress;
    private String contactLastName;
    private String contactFirstName;
    private String contactMiddleName;
    private String contactPhone;
    private String contactEmail;
    private List<TenderLotShortResponse> lots = new ArrayList<>();
}
