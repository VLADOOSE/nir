package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Компонент комплектности РУ (JSONB `product_registration.complect`). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationComponent(String component, String producer, String country) {}
