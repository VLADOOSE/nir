package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.LeadDirection;
import com.vladoose.nir.entity.LeadEventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Заметка (NOTE) или звонок (CALL + direction) в ленту обращения. */
@Data
public class LeadEventCreate {
    @NotNull private LeadEventType type;
    private LeadDirection direction;
    @NotBlank(message = "Текст пустой") @Size(max = 5000) private String body;
}
