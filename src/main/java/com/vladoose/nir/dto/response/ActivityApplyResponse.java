package com.vladoose.nir.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class ActivityApplyResponse {
    private Long id;
    private TenderShortResponse tender;
    private String status;
    private OffsetDateTime createdAt;

    private String contractNumber;
    private LocalDate contractSignedAt;
    private String deliveryStatus;
    private LocalDate deliveredAt;
    private LocalDate paidAt;
    /**
     * Always empty in responses — the frontend loads items separately
     * via GET /applies/{id}/items. Kept here for shape compatibility.
     */
    private List<Object> items = new ArrayList<>();

    /**
     * Агрегаты по позициям заявки (заполняются Enricher'ом):
     * totalRevenue   — сумма offered_cost × quantity
     * totalProcurement — сумма procurement (если известна по КП)
     * totalProfit    — totalRevenue − totalProcurement
     * marginPercent  — totalProfit / totalProcurement × 100
     */
    private BigDecimal totalRevenue;
    private BigDecimal totalProcurement;
    private BigDecimal totalProfit;
    private BigDecimal marginPercent;
}
