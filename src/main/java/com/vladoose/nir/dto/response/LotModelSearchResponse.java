package com.vladoose.nir.dto.response;

import java.time.LocalDate;
import java.util.List;

/** Поиск «модели в работе» (спека 2026-10-20-lot-model-in-work §5.4): наши товары сайта и записи реестра НЦЭЛС. */
public record LotModelSearchResponse(List<Our> ours, List<Registry> registry) {
    public record Our(long productId, String name, String brand, String sku, String regNumber, String regStatus,
                      LocalDate regExpires, String url) {}
    public record Registry(String regNumber, String name, String producer, String country, LocalDate expires,
                           boolean unlimited, boolean expired) {}
}
