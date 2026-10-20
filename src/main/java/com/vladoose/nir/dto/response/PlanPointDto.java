package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Пункт плана на странице «Планы закупок» (спека §10.2). */
public record PlanPointDto(Long id, Long extId, int year, Integer month, boolean monthPassed, String customerName, String region,
                           String name, String description, BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal amount,
                           String tradeMethod, List<String> groups, String level, OffsetDateTime firstSeenAt, String goneStatus) {}
