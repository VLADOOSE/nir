package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class PriceRequestRequest {

    @NotNull
    private Long tenderId;

    @NotNull
    private Long distributorId;

    private String status;
    private String note;
    private OffsetDateTime sentAt;
    private LocalDate responseDate;

    @Valid
    private List<PriceRequestItemRequest> items = new ArrayList<>();
}
