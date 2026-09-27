package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import lombok.Data;

import java.util.List;

/** Обёртка, чтобы @Valid дошёл до элементов списка позиций. */
@Data
public class LeadItemsUpdate {
    @Valid private List<LeadItemDto> items;
}
