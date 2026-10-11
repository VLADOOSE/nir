package com.vladoose.nir.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    /** Время окончания приёма с площадки — местное время площадки; пусто у ручных тендеров (B2). */
    private LocalDateTime deadlineAt;
    private LocalDate publishDate;
    private BigDecimal totalCost;
    private String currency;
    private String source;
    private String platform;
    private String description;
    private String deliveryAddress;
    private String region;
    private String regionKato;
    private String customerName;
    private String customerBin;
    private String contactLastName;
    private String contactFirstName;
    private String contactMiddleName;
    private String contactPhone;
    private String contactEmail;
    private List<TenderLotShortResponse> lots = new ArrayList<>();
}
