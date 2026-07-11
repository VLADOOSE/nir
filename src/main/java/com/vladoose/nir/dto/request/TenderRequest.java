package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class TenderRequest {

    @NotBlank(message = "Номер тендера обязателен")
    private String tenderNumber;

    private Long facilityId;

    @NotBlank(message = "Статус обязателен")
    private String status;

    @NotBlank(message = "Способ закупки обязателен")
    private String purchaseType;

    private String platform;

    @NotNull(message = "Дедлайн обязателен")
    private LocalDate deadline;

    private LocalDate publishDate;

    @PositiveOrZero(message = "Стоимость не может быть отрицательной")
    private BigDecimal totalCost;

    private String currency;

    private String description;

    private String deliveryAddress;

    private String contactLastName;
    private String contactFirstName;
    private String contactMiddleName;
    private String contactPhone;
    private String contactEmail;
}
