package com.vladoose.nir.integration.westmed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** «Запрос КП» сайта — корзина товаров с количествами. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WestmedQuoteRequest(String id, String name, String email, String phone, String company,
                                  String message, String status, List<Item> items, String createdAt) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String productSlug, String productName, Integer quantity) {}
}
