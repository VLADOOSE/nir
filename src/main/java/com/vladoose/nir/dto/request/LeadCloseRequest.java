package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.LeadCloseReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LeadCloseRequest {
    @NotNull(message = "Укажите причину закрытия") private LeadCloseReason reason;
    @Size(max = 1000) private String comment;
}
