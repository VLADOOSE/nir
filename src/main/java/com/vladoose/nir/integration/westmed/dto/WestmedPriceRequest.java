package com.vladoose.nir.integration.westmed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** «Запрос цены» сайта: productName есть — по товару, нет — общая заявка текстом. Пустые поля сайт не присылает. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WestmedPriceRequest(String id, String name, String email, String phone, String company,
                                  String message, String productName, String status, String createdAt) {}
