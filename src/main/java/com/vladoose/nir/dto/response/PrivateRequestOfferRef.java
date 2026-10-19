package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/** КП клиенту по заявке — строка раздела «КП клиенту» на странице заявки (спека 2026-10-12 §8); origin — BUILDER / FILE. */
public record PrivateRequestOfferRef(Long id, Integer number, LocalDate offerDate, String status, int itemCount,
                                     BigDecimal totalAmount, String currency, String origin) {}
