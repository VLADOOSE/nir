package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.ClientOfferStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class ClientOfferListItemResponse {
    private Long id;
    private Integer number;
    private LocalDate offerDate;
    private ClientOfferStatus status;
    private String clientName;
    private int itemCount;
    private BigDecimal totalAmount;
    private String currency;
    private Long tenderId;
    private OffsetDateTime updatedAt;
}
