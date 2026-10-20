package com.vladoose.nir.integration.westmed.dto;

import com.vladoose.nir.entity.SiteProductAttribute;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Товар каталога сайта: из админ-API — с описанием, характеристиками и РУ; из публичного списка — без них. */
public record WestmedCatalogItem(UUID id, String name, String sku, String slug, String brand, String category,
                                 String shortDescription, String description, List<SiteProductAttribute> attributes,
                                 String registrationCert, Instant updatedAt) {}
