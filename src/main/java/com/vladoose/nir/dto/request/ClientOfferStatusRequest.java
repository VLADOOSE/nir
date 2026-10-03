package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.ClientOfferStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ClientOfferStatusRequest {
    @NotNull private ClientOfferStatus status;
}
