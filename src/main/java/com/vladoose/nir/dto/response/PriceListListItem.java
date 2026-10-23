package com.vladoose.nir.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Строка списка «Прайсы поставщиков» (без байтов файла). */
public record PriceListListItem(Long id, Long distributorId, String distributorName, String status, LocalDate listDate,
                                LocalDate validTo, boolean expired, String currency, String fileName, String kind,
                                String parseStatus, String createdBy, OffsetDateTime createdAt, long itemCount) {}
