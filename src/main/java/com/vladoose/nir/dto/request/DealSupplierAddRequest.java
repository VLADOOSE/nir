package com.vladoose.nir.dto.request;

import lombok.Data;

import java.math.BigDecimal;

/** «+ Поставщик»: из справочника рынка; amount необязательна, currency пусто — валюта рынка. */
@Data
public class DealSupplierAddRequest {
    private Long distributorId;
    private BigDecimal amount;
    private String currency;
}
