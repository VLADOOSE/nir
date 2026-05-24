package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class ActivityApplyRequest {

    private Long tenderId;

    @NotBlank(message = "Статус обязателен")
    private String status;

    private OffsetDateTime createdAt;

    private String contractNumber;
    private LocalDate contractSignedAt;
    private String deliveryStatus;
    private LocalDate deliveredAt;
    private LocalDate paidAt;
}
